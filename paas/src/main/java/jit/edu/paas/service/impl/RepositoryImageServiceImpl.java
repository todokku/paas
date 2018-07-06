package jit.edu.paas.service.impl;

import com.baomidou.mybatisplus.mapper.EntityWrapper;
import com.baomidou.mybatisplus.plugins.Page;
import com.baomidou.mybatisplus.service.impl.ServiceImpl;
import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.messages.Image;
import com.spotify.docker.client.messages.ImageInfo;
import jit.edu.paas.commons.util.*;
import jit.edu.paas.commons.util.jedis.JedisClient;
import jit.edu.paas.domain.entity.RepositoryImage;
import jit.edu.paas.domain.entity.SysImage;
import jit.edu.paas.domain.enums.ImageTypeEnum;
import jit.edu.paas.domain.enums.ResultEnum;
import jit.edu.paas.domain.vo.ResultVo;
import jit.edu.paas.mapper.RepositoryImageMapper;
import jit.edu.paas.service.RepositoryImageService;
import jit.edu.paas.service.SysImageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * <p>
 * 仓储镜像 服务实现类
 * </p>
 *
 * @author jitwxs
 * @since 2018-07-05
 */
@Service
@Slf4j
public class RepositoryImageServiceImpl extends ServiceImpl<RepositoryImageMapper, RepositoryImage> implements RepositoryImageService {
    @Autowired
    private RepositoryImageMapper repositoryImageMapper;
    @Autowired
    private SysImageService sysImageService;
    @Autowired
    private JedisClient jedisClient;
    @Autowired
    private DockerClient dockerClient;

    @Value("${docker.registry.url}")
    private String registryUrl;

    @Value("${redis.repository.image.key}")
    private String key;
    private String ID_PREFIX = "ID:";
    private String NAME_PREFIX = "NAME:";

    @Override
    public RepositoryImage getById(String id) {
        String field = ID_PREFIX + id;
        try {
            String json = jedisClient.hget(key, field);
            if(StringUtils.isNotBlank(json)) {
                return JsonUtils.jsonToObject(json, RepositoryImage.class);
            }
        } catch (Exception e) {
            log.error("缓存读取异常，错误位置：RepositoryImageServiceImpl.getById()");
        }

        RepositoryImage repositoryImage = repositoryImageMapper.selectById(id);
        if(repositoryImage == null) {
            return null;
        }

        try {
            jedisClient.hset(key, field, JsonUtils.objectToJson(repositoryImage));
        } catch (Exception e) {
            log.error("缓存存储异常，错误位置：RepositoryImageServiceImpl.getById()");
        }

        return repositoryImage;
    }

    @Override
    public List<RepositoryImage> listByName(String name) {
        String field = NAME_PREFIX + name;
        try {
            String json = jedisClient.hget(key, field);
            if(StringUtils.isNotBlank(json)) {
                return JsonUtils.jsonToList(json, RepositoryImage.class);
            }
        } catch (Exception e) {
            log.error("缓存读取异常，错误位置：RepositoryImageServiceImpl.listByName()");
        }

        List<RepositoryImage> list = repositoryImageMapper.selectList(
                new EntityWrapper<RepositoryImage>().eq("name", name));
        if(list == null) {
            return null;
        }

        try {
            jedisClient.hset(key, field, JsonUtils.objectToJson(list));
        } catch (Exception e) {
            log.error("缓存存储异常，错误位置：RepositoryImageServiceImpl.listByName()");
        }

        return list;
    }

    @Override
    public Page<RepositoryImage> listRepositoryFromDb(Page<RepositoryImage> page) {
        // 注意！！ 分页 total 是经过插件自动 回写 到传入 page 对象
        return page.setRecords(repositoryImageMapper.listRepositoryFromDb(page));
    }

    @Override
    public List<String> listRepositoryFromHub() throws Exception {
        return DockerRegistryApiUtils.listRepositories(registryUrl);
    }

    @Override
    public List<String> listTagsFromHub(String fullName) throws Exception  {
        return DockerRegistryApiUtils.listTags(registryUrl, fullName);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResultVo sync() {
        // 1、遍历数据库
        List<RepositoryImage> dbImage = repositoryImageMapper.selectList(new EntityWrapper<>());
        boolean[] dbFlag = new boolean[dbImage.size()];
        Arrays.fill(dbFlag, false);
        int addCount = 0, deleteCount = 0, errorCount = 0;

        try {
            // 2、遍历Hub
            List<String> names = listRepositoryFromHub();
            for(String name : names) {
                List<String> tags = listTagsFromHub(name);
                if(tags != null) {
                    for(String tag : tags) {
                        // 拼接FullName
                        String fullName = registryUrl + "/" + name + ":" + tag;
                        // 判断本地是否存在
                        boolean flag = false;
                        for(int i=0; i < dbFlag.length; i++) {
                            // 跳过验证过的
                            if(dbFlag[i]) {
                                continue;
                            }
                            if(fullName.equals(dbImage.get(i).getFullName())) {
                                dbFlag[i] = true;
                                flag = true;
                                break;
                            }
                        }

                        // 如果不存在，表示数据库中没有该记录，新增记录
                        if(!flag) {
                            RepositoryImage image = imageName2RepositoryImage(fullName);
                            if(image != null) {
                                repositoryImageMapper.insert(image);
                                addCount++;
                            } else {
                                errorCount++;
                            }
                        }
                    }
                }
            }

            // 3、清理失效的记录
            for(int i=0; i<dbFlag.length ;i++) {
                if(!dbFlag[i]) {
                    deleteCount++;
                    repositoryImageMapper.deleteById(dbImage.get(i));
                }
            }

            // 4、准备返回值
            Map<String, Integer> map = new HashMap<>(16);
            map.put("delete", deleteCount);
            map.put("add", addCount);
            map.put("error", errorCount);

            return ResultVoUtils.success(map);
        } catch (Exception e) {
            log.error("读取Hub数据失败，错误位置：{}，错误信息：{}",
                    "RepositoryImageServiceImpl.sync()",HttpClientUtils.getStackTraceAsString(e));
            return ResultVoUtils.error(ResultEnum.NETWORK_ERROR);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResultVo pushToHub(String sysImageId, String userId) {
        SysImage sysImage = sysImageService.getById(sysImageId);
        if(sysImage == null) {
            return ResultVoUtils.error(ResultEnum.IMAGE_EXCEPTION);
        }

        // 判断镜像类型
        if(ImageTypeEnum.LOCAL_USER_IMAGE.getCode() != sysImage.getType()) {
            return ResultVoUtils.error(ResultEnum.PUBLIC_IMAGE_UPLOAD_ERROR);
        }
        // 判断镜像所属
        if(!userId.equals(sysImage.getUserId())) {
            return ResultVoUtils.error(ResultEnum.PERMISSION_ERROR);
        }

        try {
            // 1、创建镜像tag
            // 命名规则：registryUrl/userId/name:tag
            String newName = registryUrl + "/" + userId + "/" + sysImage.getName() + ":" + sysImage.getTag();
            // 判断是否存在
            if(hasExist(newName)) {
                return ResultVoUtils.error(ResultEnum.IMAGE_UPLOAD_ERROR_BY_EXIST);
            }

            dockerClient.tag(sysImage.getFullName(),newName);
            // 2、上传镜像
            dockerClient.push(newName);
            // 3、删除镜像tag
            dockerClient.removeImage(newName);
            // 4、保存数据库
            RepositoryImage image = imageName2RepositoryImage(newName);
            repositoryImageMapper.insert(image);
            // 5、清理缓存
            cleanCache(null, image.getName());

            return ResultVoUtils.success();
        } catch (Exception e) {
            log.error("上传镜像失败，错误位置：{}，错误信息：{}",
                    "RepositoryImageServiceImpl.pushToHub()", HttpClientUtils.getStackTraceAsString(e));
            return ResultVoUtils.error(ResultEnum.PUSH_ERROR);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResultVo pullFromHub(String id) {
        RepositoryImage repositoryImage = getById(id);
        if(repositoryImage == null) {
            return ResultVoUtils.error(ResultEnum.IMAGE_EXCEPTION);
        }

        String fullName = repositoryImage.getFullName();

        // 判断本地是否存在
        if(sysImageService.getByFullName(fullName) != null) {
            return ResultVoUtils.error(ResultEnum.PULL_ERROR_BY_EXIST);
        }

        try {
            // 1、拉取镜像
            dockerClient.pull(fullName);
            // 2、插入数据
            SysImage sysImage = repositoryImage2SysImage(repositoryImage);
            sysImageService.insert(sysImage);

            return ResultVoUtils.success();
        } catch (Exception e) {
            log.error("拉取镜像失败，错误位置：{}，错误信息：{}",
                    "RepositoryImageServiceImpl.pullFromHub()", HttpClientUtils.getStackTraceAsString(e));
            return ResultVoUtils.error(ResultEnum.PULL_ERROR);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResultVo deleteFromHub(String id) {
        RepositoryImage repositoryImage = getById(id);
        if(repositoryImage == null) {
            return ResultVoUtils.error(ResultEnum.IMAGE_EXCEPTION);
        }

        String name = repositoryImage.getName();
        String digest = repositoryImage.getDigest();
        if(StringUtils.isBlank(name, digest)) {
            log.error("Hub镜像信息不完整，目标ID：{}，目标Name：{}，目标digest：{}",
                    id, name, digest);
            return ResultVoUtils.error(ResultEnum.IMAGE_EXCEPTION);
        }

        try {
            // 删除镜像
            DockerRegistryApiUtils.deleteImage(registryUrl, name, digest);
            // 删除数据
            repositoryImageMapper.deleteById(id);
            // 清理缓存
            cleanCache(id, name);

            return ResultVoUtils.success();
        } catch (Exception e) {
            log.error("删除Hub镜像失败，错误位置：{}，错误信息：{}",
                    "RepositoryImageServiceImpl.deleteFromHub()", HttpClientUtils.getStackTraceAsString(e));
            return ResultVoUtils.error(ResultEnum.DELETE_HUB_IMAGE_ERROR);
        }
    }

    @Override
    public String getDigest(String name, String tag) throws Exception {
        return DockerRegistryApiUtils.getDigest(registryUrl, name, tag);
    }

    @Override
    public void cleanCache(String id, String name) {
        try {
            if(StringUtils.isNotBlank(id)) {
                jedisClient.hdel(key, ID_PREFIX + id);
            }
            if(StringUtils.isNotBlank(name)) {
                jedisClient.hdel(key, NAME_PREFIX + name);
            }
        } catch (Exception e) {
            log.error("清理缓存失败，错误位置：{}，目标id：{}，目标name：{}",
                    "RepositoryImageServiceImpl.cleanCache()", id, name);
        }
    }

    @Override
    public Boolean hasExist(String fullName) {
        List<RepositoryImage> list = repositoryImageMapper.selectList(
                new EntityWrapper<RepositoryImage>().eq("full_name", fullName));

        RepositoryImage repositoryImage = CollectionUtils.getListFirst(list);

        return repositoryImage != null;
    }

    /**
     * 镜像名 --> RepositoryImage
     * @author jitwxs
     * @since 2018/7/5 23:04
     */
    private RepositoryImage imageName2RepositoryImage(String name) {
        // 形如： 192.168.100.183:5000/hello-world-1313asfa:latest
        RepositoryImage image = new RepositoryImage();

        // 设置完整名
        image.setFullName(name);

        // 1、获取仓储地址
        int i = name.indexOf("/");
        String url = name.substring(0, i);
        image.setRepo(url);

        // 2、获取tag
        // 1313asfa/hello-world:latest
        String body = name.substring(i+1);
        String[] split = body.split(":");
        // 长度为1或2正常
        if(split.length > 2) {
            return null;
        }
        String tag = split.length == 2 ? split[1] : "latest";
        image.setTag(tag);

        // 3、获取名称和用户ID
        // 1313asfa/hello-world
        body = split[0];
        i = body.indexOf("/");
        image.setName(body);
        image.setUserId(body.substring(0,i));

        // 4、设置Digest
        try {
            String digest = getDigest(body, image.getTag());
            image.setDigest(digest);
        } catch (Exception e) {
            image.setDigest(null);
        }

        return image;
    }

    /**
     * RepositoryImage --> SysImage
     * @author jitwxs
     * @since 2018/7/5 23:28
     */
    private SysImage repositoryImage2SysImage(RepositoryImage repositoryImage) {
        SysImage sysImage = new SysImage();
        String fullName = repositoryImage.getFullName();

        // 1、设置公共信息
        sysImage.setFullName(fullName);
        sysImage.setName(repositoryImage.getName());
        sysImage.setTag(repositoryImage.getTag());
        sysImage.setRepo(repositoryImage.getRepo());

        // 2、设置Type
        // Hub上的镜像拉取下来类型是公共镜像
        sysImage.setType(ImageTypeEnum.LOCAL_PUBLIC_IMAGE.getCode());

        try {
            // 3、设置其他信息
            List<Image> images = dockerClient.listImages(DockerClient.ListImagesParam.byName(fullName));
            if(images.size()  != 0) {
                Image image = images.get(0);
                // 设置ImageId
                sysImage.setImageId(splitImageId(image.id()));
                // 设置大小
                sysImage.setSize(image.size());
                // 设置虚拟大小
                sysImage.setVirtualSize(image.virtualSize());
                // 设置Label
                sysImage.setLabels(JsonUtils.mapToJson(image.labels()));
                // 设置父节点
                sysImage.setParentId(image.parentId());
                sysImage.setCreateDate(new Date());
            }
            // 设置CMD
            ImageInfo info = dockerClient.inspectImage(fullName);
            sysImage.setCmd(JsonUtils.objectToJson(info.containerConfig().cmd()));
        } catch (Exception e) {
            log.error("读取镜像信息异错误，错误位置：{}，错误信息：{}",
                    "RepositoryImageServiceImpl.repositoryImage2SysImage()", HttpClientUtils.getStackTraceAsString(e));
        }

        return sysImage;
    }

    /**
     * 拆分ImageId，去掉头部，如：
     * sha256:e38bc07ac18ee64e6d59cf2eafcdddf9cec2364dfe129fe0af75f1b0194e0c96
     * -> e38bc07ac18ee64e6d59cf2eafcdddf9cec2364dfe129fe0af75f1b0194e0c96
     * @author jitwxs
     * @since 2018/7/4 9:44
     */
    private String splitImageId(String imageId) {
        String[] splits = imageId.split(":");
        if(splits.length == 1) {
            return imageId;
        }

        return splits[1];
    }
}
