package com.mall.core.storage;

import com.mall.common.exception.BusinessException;
import com.mall.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 本机磁盘存储：{@code mall.upload.dir}/{yyyyMM}/{uuid}.{ext}，对外 URL 为 {@code /uploads/...}。
 *
 * <p>为什么不把文件放进项目目录下的 static：jar 内置资源在打包后是只读的，
 * 而且重启/重新部署会丢。存独立目录，部署时挂 volume 即可。
 */
@Slf4j
@Service
public class LocalFileStorage implements FileStorage {

    /** 只接受这几种图片：评价晒图不需要支持 svg（svg 能带脚本）和 gif（体积不可控） */
    private static final Set<String> ALLOWED_EXT = Set.of("jpg", "jpeg", "png", "webp");
    private static final Set<String> ALLOWED_MIME = Set.of("image/jpeg", "image/png", "image/webp");

    private final Path root;
    private final String publicBase;

    public LocalFileStorage(@Value("${mall.upload.dir:./uploads}") String dir,
                            @Value("${mall.upload.public-base:}") String publicBase) {
        this.root = Paths.get(dir).toAbsolutePath().normalize();
        // 末尾去掉斜杠，拼 URL 时统一由这里加
        this.publicBase = publicBase == null ? "" : publicBase.replaceAll("/+$", "");
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new IllegalStateException("cannot create upload dir: " + this.root, e);
        }
        log.info("[upload] local storage root={} publicBase={}", this.root, this.publicBase);
    }

    @Override
    public String saveImage(byte[] bytes, String originalName, String contentType) {
        if (bytes == null || bytes.length == 0) {
            throw new BusinessException(ResultCode.UPLOAD_INVALID, "文件为空");
        }
        String ext = extensionOf(originalName);
        if (!ALLOWED_EXT.contains(ext)) {
            throw new BusinessException(ResultCode.UPLOAD_INVALID, "只支持 jpg/png/webp");
        }
        if (contentType != null && !ALLOWED_MIME.contains(contentType.toLowerCase(Locale.ROOT))) {
            // 扩展名可以伪造，MIME 也可以，但两者对不上基本就是伪装文件，直接拒
            throw new BusinessException(ResultCode.UPLOAD_INVALID, "文件类型不支持");
        }
        String month = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
        String name = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Path dir = root.resolve(month).normalize();
        // 目录是我自己拼的（month 来自日期、name 是 UUID），这里再兜一层防穿越
        if (!dir.startsWith(root)) {
            throw new BusinessException(ResultCode.UPLOAD_INVALID, "非法路径");
        }
        try {
            Files.createDirectories(dir);
            Files.write(dir.resolve(name), bytes);
        } catch (IOException e) {
            log.error("[upload] write failed", e);
            throw new BusinessException(ResultCode.UPLOAD_INVALID, "保存失败，请重试");
        }
        return publicBase + "/uploads/" + month + "/" + name;
    }

    @Override
    public void delete(String url) {
        String prefix = publicBase + "/uploads/";
        if (url == null || !url.startsWith(prefix)) {
            return;
        }
        try {
            Path file = root.resolve(url.substring(prefix.length())).normalize();
            // 只允许删 root 下面的文件，防止 ../ 穿越
            if (file.startsWith(root)) {
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            log.warn("[upload] delete failed: {}", url, e);
        }
    }

    private static String extensionOf(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
