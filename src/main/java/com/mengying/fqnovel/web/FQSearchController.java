package com.mengying.fqnovel.web;

import com.mengying.fqnovel.dto.FQDirectoryRequest;
import com.mengying.fqnovel.dto.FQDirectoryResponse;
import com.mengying.fqnovel.dto.FQNovelResponse;
import com.mengying.fqnovel.dto.FQSearchRequest;
import com.mengying.fqnovel.dto.FQSearchResponse;
import com.mengying.fqnovel.service.FQDirectoryService;
import com.mengying.fqnovel.service.FQSearchService;
import com.mengying.fqnovel.utils.Texts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;

/**
 * FQ书籍搜索和目录控制器（精简版，仅支持 Legado 阅读）
 * 提供书籍搜索、目录获取等API接口
 */
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
public class FQSearchController {

    private static final Logger log = LoggerFactory.getLogger(FQSearchController.class);

    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_QUERY_LENGTH = 100;
    private static final int MAX_TAB_TYPE = 20;

    private final FQSearchService fqSearchService;
    private final FQDirectoryService fqDirectoryService;

    public FQSearchController(FQSearchService fqSearchService, FQDirectoryService fqDirectoryService) {
        this.fqSearchService = fqSearchService;
        this.fqDirectoryService = fqDirectoryService;
    }

    /**
     * 搜索书籍
     * 路径: /search?key={关键词}&page=1&size=20&tabType=3
     *
     * @param key 搜索关键词
     * @param page 页码（从1开始，默认1）
     * @param size 每页数量（默认20）
     * @param tabType 搜索类型（默认3）
     * @param searchId 搜索ID（可选，用于翻页）
     * @return 搜索结果
     */
    @GetMapping("/search")
    public CompletableFuture<FQNovelResponse<FQSearchResponse>> searchBooks(
            @RequestParam String key,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size,
            @RequestParam(defaultValue = "3") Integer tabType,
            @RequestParam(required = false) String searchId) {

        if (log.isDebugEnabled()) {
            log.debug("搜索书籍 - key: {}, page: {}, size: {}, tabType: {}", key, page, size, tabType);
        }

        String trimmedKey = Texts.trimToNull(key);
        if (!Texts.hasText(trimmedKey)) {
            return badRequest("搜索关键词不能为空");
        }
        if (trimmedKey.length() > MAX_QUERY_LENGTH) {
            return badRequest("搜索关键词过长");
        }
        if (page == null || page < 1) {
            return badRequest("页码必须大于等于1");
        }
        if (size == null || size < 1 || size > MAX_PAGE_SIZE) {
            return badRequest("size 超出范围（1-50）");
        }
        if (tabType == null || tabType < 1 || tabType > MAX_TAB_TYPE) {
            return badRequest("tabType 超出范围");
        }

        // 将页码转换为offset（页码从1开始，offset从0开始）
        long offsetLong = ((long) page - 1L) * size;
        if (offsetLong > Integer.MAX_VALUE) {
            return badRequest("分页参数过大");
        }
        int offset = (int) offsetLong;

        // 构建搜索请求
        FQSearchRequest searchRequest = new FQSearchRequest();
        searchRequest.setQuery(trimmedKey);
        searchRequest.setOffset(offset);
        searchRequest.setCount(size);
        searchRequest.setTabType(tabType);
        searchRequest.setSearchId(Texts.trimToNull(searchId));
        searchRequest.setPassback(offset);

        return fqSearchService.searchBooksEnhanced(searchRequest)
            .thenApply(response -> {
                if (response == null) {
                    return FQNovelResponse.<FQSearchResponse>error("搜索失败: 空响应");
                }
                if (response.code() == null) {
                    return FQNovelResponse.<FQSearchResponse>error("搜索失败: 响应码为空");
                }
                if (response.code() != 0) {
                    return FQNovelResponse.<FQSearchResponse>error(response.code(), response.message());
                }
                return FQNovelResponse.success(response.data());
            });
    }

    /**
     * 获取书籍目录
     * 路径: /toc/{bookId}（bookId 仅允许数字）
     *
     * @param bookId 书籍ID
     * @return 书籍目录
     */
    @GetMapping("/toc/{bookId:\\d+}")
    public CompletableFuture<FQNovelResponse<FQDirectoryResponse>> getBookToc(
            @PathVariable String bookId) {

        if (log.isDebugEnabled()) {
            log.debug("获取书籍目录 - bookId: {}", bookId);
        }

        String normalizedBookId = Texts.trimToNull(bookId);
        if (!Texts.isDigits(normalizedBookId)) {
            return badRequest("书籍ID必须为纯数字");
        }

        // 构建目录请求
        FQDirectoryRequest directoryRequest = new FQDirectoryRequest();
        directoryRequest.setBookId(normalizedBookId);
        directoryRequest.setMinimalResponse(true);

        return fqDirectoryService.getBookDirectory(directoryRequest);
    }

    private static <T> CompletableFuture<FQNovelResponse<T>> badRequest(String message) {
        return CompletableFuture.completedFuture(FQNovelResponse.error(message));
    }
}
