package com.zeroai.common.base.service.impl;

import com.zeroai.common.base.service.UploadService;
import com.zeroai.util.util.ServletUtils;
import com.zeroai.util.util.http.HttpUtil;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@AllArgsConstructor
public class UploadServiceImpl implements UploadService {

    /**
     * 七牛云上传文件
     *
     * @param fileName 文件名
     * @return 上传后的文件访问路径
     */
    @Override
    public String uploadFile(String fileName) {
        String requestUrl = HttpUtil.getRequestContext(ServletUtils.getRequest());
        return requestUrl + "/upload/" + fileName;
    }
}
