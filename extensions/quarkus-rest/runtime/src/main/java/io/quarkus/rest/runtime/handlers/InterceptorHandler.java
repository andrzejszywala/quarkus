package io.quarkus.rest.runtime.handlers;

import javax.ws.rs.ext.ReaderInterceptor;
import javax.ws.rs.ext.WriterInterceptor;

import io.quarkus.rest.runtime.core.QuarkusRestRequestContext;

public class InterceptorHandler implements RestHandler {

    private final WriterInterceptor[] writerInterceptors;
    private final ReaderInterceptor[] readerInterceptors;

    public InterceptorHandler(WriterInterceptor[] writerInterceptors, ReaderInterceptor[] readerInterceptors) {
        this.writerInterceptors = writerInterceptors;
        this.readerInterceptors = readerInterceptors;
    }

    @Override
    public void handle(QuarkusRestRequestContext requestContext) throws Exception {
        requestContext.setWriterInterceptors(writerInterceptors);
        requestContext.setReaderInterceptors(readerInterceptors);
    }
}
