package com.mengying.fqnovel.web;

import com.mengying.fqnovel.dto.FQNovelBookInfo;
import com.mengying.fqnovel.dto.FQNovelChapterInfo;
import com.mengying.fqnovel.dto.FQNovelRequest;
import com.mengying.fqnovel.dto.FQNovelResponse;
import com.mengying.fqnovel.service.FQChapterPrefetchService;
import com.mengying.fqnovel.service.FQNovelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;

/**
 * FQNovel API 控制器（精简版，仅支持 Legado 阅读）
 * 提供小说书籍和章节内容获取接口
 */
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
public class FQNovelController {

    private static final Logger log = LoggerFactory.getLogger(FQNovelController.class);

    private final FQNovelService fqNovelService;
    private final FQChapterPrefetchService fqChapterPrefetchService;

    public FQNovelController(FQNovelService fqNovelService, FQChapterPrefetchService fqChapterPrefetchService) {
        this.fqNovelService = fqNovelService;
        this.fqChapterPrefetchService = fqChapterPrefetchService;
    }

    /**
     * 获取书籍详情（精简版 - 仅返回 Legado 需要的字段）
     * 路径: /book/{bookId}（bookId 仅允许数字）
     * 
     * @param bookId 书籍ID
     * @return 书籍详情信息（精简）
     */
    @GetMapping("/book/{bookId:\\d+}")
    public CompletableFuture<FQNovelResponse<FQNovelBookInfo>> getBookInfo(@PathVariable String bookId) {
        if (log.isDebugEnabled()) {
            log.debug("获取书籍信息 - bookId: {}", bookId);
        }
        return fqNovelService.getBookInfo(bookId);
    }

    /**
     * 获取章节正文
     * 路径: /chapter/{bookId}/{chapterId}（bookId/chapterId 仅允许数字）
     * 
     * @param bookId 书籍ID
     * @param chapterId 章节ID
     * @return 章节内容信息
     */
    @GetMapping("/chapter/{bookId:\\d+}/{chapterId:\\d+}")
    public CompletableFuture<FQNovelResponse<FQNovelChapterInfo>> getChapterContent(
            @PathVariable String bookId,
            @PathVariable String chapterId) {

        if (log.isDebugEnabled()) {
            log.debug("获取章节内容 - bookId: {}, chapterId: {}", bookId, chapterId);
        }

        FQNovelRequest request = new FQNovelRequest();
        request.setBookId(bookId);
        request.setChapterId(chapterId);
        return fqChapterPrefetchService.getChapterContent(request);
    }
}
