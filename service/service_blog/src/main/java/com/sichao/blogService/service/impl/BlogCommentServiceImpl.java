package com.sichao.blogService.service.impl;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sichao.blogService.client.UserClient;
import com.sichao.blogService.entity.Blog;
import com.sichao.blogService.entity.BlogComment;
import com.sichao.blogService.entity.BlogTopic;
import com.sichao.blogService.entity.BlogTopicRelation;
import com.sichao.blogService.entity.vo.BlogVo;
import com.sichao.blogService.entity.vo.CommentVo;
import com.sichao.blogService.mapper.BlogCommentMapper;
import com.sichao.blogService.service.BlogCommentService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.sichao.blogService.service.BlogService;
import com.sichao.blogService.service.BlogTopicRelationService;
import com.sichao.blogService.service.BlogTopicService;
import com.sichao.common.constant.Constant;
import com.sichao.common.constant.PrefixKeyConstant;
import com.sichao.common.constant.RabbitMQConstant;
import com.sichao.common.entity.MqMessage;
import com.sichao.common.entity.to.UserInfoTo;
import com.sichao.common.exceptionhandler.sichaoException;
import com.sichao.common.mapper.MqMessageMapper;
import com.sichao.common.utils.R;
import com.sichao.common.utils.RandomSxpire;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <p>
 * 评论与子评论表 服务实现类
 * </p>
 *
 * @author jicong
 * @since 2023-04-29
 */
@Service
public class BlogCommentServiceImpl extends ServiceImpl<BlogCommentMapper, BlogComment> implements BlogCommentService {
    @Autowired
    private UserClient userClient;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private RedissonClient redissonClient;
    @Autowired
    private MqMessageMapper mqMessageMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private BlogTopicRelationService blogTopicRelationService;
    @Autowired
    private BlogService blogService;

    //发布评论(博客下评论、父评论) TODO 将评论加入缓存
    @Transactional
    @Override
    public void saveComment(String curUserId, String blogId, String content) {
        //查看博客是否存在
        Blog blog = blogService.getById(blogId);
        if(blog==null){
            throw new sichaoException(Constant.FAILURE_CODE,"发布评论异常，博客不存在");
        }

        if(!StringUtils.hasText(content))
            throw new sichaoException(Constant.FAILURE_CODE,"评论内容不能为空");
        List<String> userIdList=new ArrayList<>();//用来保存用户id的集合
        StringBuilder strb=new StringBuilder();//用来拼接评论内容
        int idx = 0;

        //评论只偶有@用户功能，没有#话题#功能
        //使用正则表达式获取评论中被@的用户（@用户 ）
        //匹配以@开头，后面跟随着至少一个非'@'、非空格字符,最后匹配零个或一个空格
        Pattern referer_pattern_user = Pattern.compile("@([^@^\\s]{1,})([\\s]{0,1})");
        Matcher matchr_user = referer_pattern_user.matcher(content);
        //之前字符串中匹配到的位置不会在被匹配到，会往后开始匹配，配合while循环做到匹配整个字符串中所有符合正则表达式的子串
        while (matchr_user.find()){//为true说明匹配，为false说明不匹配
            String origion_str_user = matchr_user.group();//获取匹配到的字符串
            String userStr = origion_str_user.substring(1, origion_str_user.length()).trim();//裁剪

            //TODO 这里的循环查库优化？
            R r = (R) userClient.getUserIdByNickname(userStr);//远程调用查询用户id
            String userId = (String) r.getData().get("userId");

            //给@用户添加超链接
            //matchr.start()：获得被匹配到的子串在原串的起始位置
            strb.append(content.substring(idx, matchr_user.start()));
            if(userId!=null){
                userIdList.add(userId);//添加用户id到集合
                strb.append(Constant.BLOG_AT_USER_HYPERLINK_PREFIX)
                        .append(userId)
                        .append(Constant.BLOG_AT_USER_HYPERLINK_INFIX);
            }
            strb.append('@'+userStr);
            if(userId!=null){
                strb.append(Constant.BLOG_AT_USER_HYPERLINK_SUFFIX);
            }
            idx=matchr_user.start()+userStr.length()+1;
        }
        strb.append(content.substring(idx));

        //保存评论
        BlogComment blogComment = new BlogComment();
        blogComment.setCreatorId(curUserId);
        blogComment.setBlogId(blogId);
        blogComment.setCommentContent(strb.toString());//拼接了超链接的评论内容
        baseMapper.insert(blogComment);


        //RabbitMQ发送消息，异步实现对@用户的处理
        if(!userIdList.isEmpty()){
            String blogCommentId = blogComment.getId();//获取自动生成的id
            Map<String,Object> userMap=new HashMap<>();
            userMap.put("blogCommentId",blogCommentId);
            userMap.put("userIdList",userIdList);
            //发送消息前先记录数据
            String userMapJson = JSON.toJSONString(userMap);
            MqMessage UserMqMessage = new MqMessage(userMapJson,RabbitMQConstant.BLOG_EXCHANGE,RabbitMQConstant.BLOG_COMMENT_AT_USER_ROUTINGKEY,
                    "Map<String,Object>",(byte)0);
            mqMessageMapper.insert(UserMqMessage);

            //指定路由，给交换机发送数据，并且携带数据标识
            rabbitTemplate.convertAndSend(RabbitMQConstant.BLOG_EXCHANGE,RabbitMQConstant.BLOG_COMMENT_AT_USER_ROUTINGKEY,
                    userMap,new CorrelationData(UserMqMessage.getId()));//以mq消息表id作为数据标识

        }

        ValueOperations<String, String> ops = stringRedisTemplate.opsForValue();
        //给redis中博客评论数变化数+1
        String commentCountModifyKey = PrefixKeyConstant.BLOG_COMMENT_COUNT_MODIFY_PREFIX + blogId;
        ops.increment(commentCountModifyKey);//自增，如果key不存在，则先创建整个key且值为0，而后再自增
        //话题中讨论数+1
        List<String> topicIdList=blogTopicRelationService.getTopicIdByBlogId(blogId);
        for (String topicId : topicIdList) {
            ops.increment(PrefixKeyConstant.BLOG_TOPIC_DISCUSSION_MODIFY_PREFIX+topicId);//自增
        }

    }

    //删除评论、博客评论数-1
    @Override
    public void deleteComment(String userId, String commentId) {
        //先查看评论是否存在且当前用户是该评论的作者，是则可以删除，不是则不能做删除操作
        BlogComment blogComment = baseMapper.selectById(commentId);
        if(blogComment==null || !blogComment.getCreatorId().equals(userId)){
            throw new sichaoException(Constant.FAILURE_CODE,"删除评论异常，评论不存在或者当前用户不是该评论作者");
        }
        //删除评论
        baseMapper.deleteById(commentId);

        ValueOperations<String, String> ops = stringRedisTemplate.opsForValue();
        ops.decrement(PrefixKeyConstant.BLOG_COMMENT_COUNT_MODIFY_PREFIX+blogComment.getBlogId());
    }


    //查询指定博客id下的评论(升序)(使用redis的list数据类型缓存)
    @Override
    public Map<String,Object> getCommentByBlogId(String blogId,int start,int limit) {
        ListOperations<String, String> forList = stringRedisTemplate.opsForList();//规定为右侧插入时间新的数据(旧时间(左)->新时间(右))
        String commentListLockKey = PrefixKeyConstant.BLOG_COMMENT_LOCK_PREFIX + blogId;//博客下评论查询锁key
        String commentListKey = PrefixKeyConstant.BLOG_COMMENT_PREFIX + blogId;//博客下评论key

        List<String> list=null;
        //判断查询是否溢出
        Long size = forList.size(commentListKey);//key不存在时，结果为0
        if(size !=null && size > 0){
            if(start >= size){
                return null;//此时查询的条数超过总评论数，直接返回null
            }else {
                //根据从start开始从左往右查询指定的博客//如果无这个key，则这句代码查询到的会使一个空的集合
                 list = forList.range(commentListKey, start, Math.min(start+limit-1,size-1));
            }
        }
        if(list==null || list.isEmpty()){
            RLock lock = redissonClient.getLock(commentListLockKey);
            lock.lock();//加锁，阻塞
            try{
                //根据博客id升序查询评论并加入缓存
                getCommentByBlogIdCache(blogId);
                //查询缓存赋值给list给后面使用
                size = forList.size(commentListKey);
                list = forList.range(commentListKey, start, Math.min(start+limit-1,size-1));
            }finally {
                lock.unlock();//解锁
            }
        }

        if(list==null)return null;
        List<CommentVo> commentVoList=new ArrayList<>();
        for (String jsonStr : list) {
            commentVoList.add(JSON.parseObject(jsonStr,CommentVo.class));
        }
        Map<String,Object> map=new HashMap<>();
        map.put("commentList",commentVoList);
        map.put("end",Math.min(start+limit-1,size-1));
        return map;
    }

    //查询指定博客id下的评论（倒序）
    /**使用redis的list类型做为存放实时评论的容器，规定只能从右侧插入，左侧的是创建时间小的数据，右侧的是创建时间大的数据。
     * start:>=0 本次要查询的评论从start位置从右往左查询     -2：说明用户第一次进入查询降序实时评论的页面   -1：说明所有评论已经查询出来了
     * 使用start控制要查询的数据，limit控制查询的长度，
     * 当评论创建时，从右侧插入数据，而原先在查询数据的用户不用因为新插入的数据影响评论浏览，只有在用户刷新页面时，才会从list的最右边开始查询
     */
    @Override
    public Map<String,Object> getCommentByBlogIdDesc(String blogId,int start,int limit) {
        ListOperations<String, String> forList = stringRedisTemplate.opsForList();//规定为右侧插入时间新的数据(旧时间(左)->新时间(右))
        String commentListLockKey = PrefixKeyConstant.BLOG_COMMENT_LOCK_PREFIX + blogId;//博客下评论查询锁key
        String commentListKey = PrefixKeyConstant.BLOG_COMMENT_PREFIX + blogId;//博客下评论key

        if(start==-1)return null;//所有评论已经查询出来了,直接返回null
        List<String> list=null;
        Long size = forList.size(commentListKey);//key不存在时，结果为0
        if(size !=null && size >0){//key中有数据则进行处理
            //判断查询是否溢出
            if(start>=size)return null;//此时查询的条数超过总评论数，直接返回null
            else if(start>=0) list = forList.range(commentListKey,Math.max(start-limit+1,0),start);//从右往左拿里limit条数据
            else if(start==-2)list = forList.range(commentListKey, Math.max(size-limit,0),size-1);//从最右边开始往左拿limit条数据
        }//key中无数据则查询数据库

        if(list==null || list.isEmpty()){
            RLock lock = redissonClient.getLock(commentListLockKey);
            lock.lock();//加锁，阻塞
            try{
                //根据博客id升序查询评论并加入缓存
                getCommentByBlogIdCache(blogId);
                //查询缓存赋值给list给后面使用
                size = forList.size(commentListKey);
                if(size !=null && size >0){//key中有数据则进行处理
                    //判断查询是否溢出
                    if(start>=size)return null;//此时查询的条数超过总评论数，直接返回null
                    else if(start>=0) list = forList.range(commentListKey,Math.max(start-limit+1,0),start);//从右往左拿里limit条数据
                    else if(start==-2)list = forList.range(commentListKey, Math.max(size-limit,0),size-1);//从最右边开始往左拿limit条数据
                }
            }finally {
                lock.unlock();//解锁
            }
        }

        if(list==null)return null;
        List<CommentVo> commentVoList=new ArrayList<>();
        for(int i=list.size() - 1; i >= 0; i--){
            commentVoList.add(JSON.parseObject(list.get(i),CommentVo.class));
        }
        int end=0;
        if(start>=0) end=Math.max(start - limit + 1, 0);
        else if(start==-2)end= (int) Math.max(size-limit,0);

        Map<String,Object> map=new HashMap<>();
        map.put("commentList",commentVoList);
        map.put("end",end);
        return map;
    }

    //根据博客id升序查询评论并加入缓存
    public void getCommentByBlogIdCache(String blogId){
        ListOperations<String, String> forList = stringRedisTemplate.opsForList();//规定为右侧插入时间新的数据(旧时间(左)->新时间(右))
        String commentListKey = PrefixKeyConstant.BLOG_COMMENT_PREFIX + blogId;//博客下评论key

        //双查机制，在锁内再查一遍缓存中是否有数据
        List<String> list = forList.range(commentListKey, 0, 0);
        if(list==null || list.isEmpty()){//缓存不存在
            //查询博客下所有实时评论(根据创建时间升序查询)
            List<CommentVo> commentVoList = baseMapper.getCommentByBlogId(blogId);
            for (CommentVo commentVo : commentVoList) {
                //保存到redis的list中（从右侧插入时间大的数据)
                forList.rightPush(commentListKey,JSON.toJSONString(commentVo));
            }
            //为key设置生存时长
            stringRedisTemplate.expire(commentListKey,
                    Constant.FIVE_MINUTES_EXPIRE + RandomSxpire.getMinRandomSxpire(),
                    TimeUnit.MILLISECONDS);
        }
    }

    //删除评论(批量)
    @Transactional
    @Override
    public void deleteCommentBatchByBlogId(String blogId) {
        QueryWrapper<BlogComment> wrapper = new QueryWrapper<>();
        wrapper.eq("blog_id",blogId);
        baseMapper.delete(wrapper);
    }
}
