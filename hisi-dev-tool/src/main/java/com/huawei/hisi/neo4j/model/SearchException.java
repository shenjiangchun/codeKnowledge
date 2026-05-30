package com.huawei.hisi.neo4j.model;

/**
 * 搜索异常
 * 用于在搜索过程中抛出带有特定错误码的异常
 */
public class SearchException extends RuntimeException {

    private final SearchErrorCode errorCode;

    public SearchException(SearchErrorCode errorCode) {
        super(errorCode.getUserMessage());
        this.errorCode = errorCode;
    }

    public SearchException(SearchErrorCode errorCode, String detailMessage) {
        super(detailMessage);
        this.errorCode = errorCode;
    }

    public SearchException(SearchErrorCode errorCode, Throwable cause) {
        super(errorCode.getUserMessage(), cause);
        this.errorCode = errorCode;
    }

    public SearchException(SearchErrorCode errorCode, String detailMessage, Throwable cause) {
        super(detailMessage, cause);
        this.errorCode = errorCode;
    }

    public SearchErrorCode getErrorCode() {
        return errorCode;
    }
}
