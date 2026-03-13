package com.mengying.fqnovel.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 搜索响应 DTO（仅保留当前接口链路需要字段）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FQSearchResponse {

    private List<BookItem> books;
    private Integer total;
    private Boolean hasMore;
    private String searchId;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BookItem {
        private String bookId;
        private String bookName;
        private String author;
        private String description;
        private String coverUrl;
        private String lastChapterTitle;
        private String category;
        private Long wordCount;

        public String getBookId() {
            return bookId;
        }

        public void setBookId(String bookId) {
            this.bookId = bookId;
        }

        public String getBookName() {
            return bookName;
        }

        public void setBookName(String bookName) {
            this.bookName = bookName;
        }

        public String getAuthor() {
            return author;
        }

        public void setAuthor(String author) {
            this.author = author;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getCoverUrl() {
            return coverUrl;
        }

        public void setCoverUrl(String coverUrl) {
            this.coverUrl = coverUrl;
        }

        public String getLastChapterTitle() {
            return lastChapterTitle;
        }

        public void setLastChapterTitle(String lastChapterTitle) {
            this.lastChapterTitle = lastChapterTitle;
        }

        public String getCategory() {
            return category;
        }

        public void setCategory(String category) {
            this.category = category;
        }

        public Long getWordCount() {
            return wordCount;
        }

        public void setWordCount(Long wordCount) {
            this.wordCount = wordCount;
        }
    }

    public List<BookItem> getBooks() {
        return books;
    }

    public void setBooks(List<BookItem> books) {
        this.books = books;
    }

    public Integer getTotal() {
        return total;
    }

    public void setTotal(Integer total) {
        this.total = total;
    }

    public Boolean getHasMore() {
        return hasMore;
    }

    public void setHasMore(Boolean hasMore) {
        this.hasMore = hasMore;
    }

    public String getSearchId() {
        return searchId;
    }

    public void setSearchId(String searchId) {
        this.searchId = searchId;
    }
}
