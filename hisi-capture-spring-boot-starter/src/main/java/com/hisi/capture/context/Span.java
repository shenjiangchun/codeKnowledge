package com.hisi.capture.context;

public class Span {
    /** 方法签名：ClassName.methodName(ParamType1,ParamType2) */
    private String methodSignature;
    /** 方法入参（限大小 + 加密） */
    private Object[] args;
    /** 方法返回值（限大小 + 加密） */
    private Object retVal;
    /** 抛出的异常（如有） */
    private Throwable exception;
    private long startMillis, endMillis;

    public Span(String methodSignature, long startMillis) {
        this.methodSignature = methodSignature;
        this.startMillis = startMillis;
    }

    public String getMethodSignature() { return methodSignature; }
    public void setMethodSignature(String methodSignature) { this.methodSignature = methodSignature; }
    public Object[] getArgs() { return args; }
    public void setArgs(Object[] args) { this.args = args; }
    public Object getRetVal() { return retVal; }
    public void setRetVal(Object retVal) { this.retVal = retVal; }
    public Throwable getException() { return exception; }
    public void setException(Throwable exception) { this.exception = exception; }
    public long getStartMillis() { return startMillis; }
    public void setStartMillis(long startMillis) { this.startMillis = startMillis; }
    public long getEndMillis() { return endMillis; }
    public void setEndMillis(long endMillis) { this.endMillis = endMillis; }
}
