package com.mall.portal.controller;

import com.mall.common.exception.BusinessException;
import com.mall.common.result.R;
import com.mall.common.result.ResultCode;
import com.mall.core.storage.FileStorage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Tag(name = "文件上传")
@RestController
@RequestMapping("/api/v1/portal/upload")
@RequiredArgsConstructor
public class UploadController {

    private final FileStorage fileStorage;

    @Value("${mall.upload.max-bytes:2097152}")
    private long maxBytes;

    @Operation(summary = "上传评价图片（jpg/png/webp，≤2MB），返回可直接展示的 URL")
    @PostMapping("/image")
    public R<String> image(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.UPLOAD_INVALID, "请选择图片");
        }
        // multipart 的全局限制之外再挡一层：不同环境配的 max-file-size 可能被放宽
        if (file.getSize() > maxBytes) {
            throw new BusinessException(ResultCode.UPLOAD_INVALID,
                    "图片不能超过 " + (maxBytes / 1024 / 1024) + "MB");
        }
        return R.ok(fileStorage.saveImage(file.getBytes(), file.getOriginalFilename(), file.getContentType()));
    }
}
