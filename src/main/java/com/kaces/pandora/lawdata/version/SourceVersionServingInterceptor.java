package com.kaces.pandora.lawdata.version;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

/** One server-owned read lease through synchronous or asynchronous response completion. */
public final class SourceVersionServingInterceptor implements AsyncHandlerInterceptor {
    private static final String ATTRIBUTE=SourceVersionServingInterceptor.class.getName()+".lease";
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(SourceVersionServingInterceptor.class);
    private final SourceVersionReadLeases leases;
    public SourceVersionServingInterceptor(SourceVersionReadLeases leases) {this.leases=leases;}
    @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) throws IOException {
        Object existing=request.getAttribute(ATTRIBUTE);
        if(existing instanceof Holder holder)return !holder.closed.get() || reject(response);
        if(existing!=null)return reject(response);
        try {request.setAttribute(ATTRIBUTE,new Holder(leases.acquire()));return true;}
        catch(IllegalStateException unavailable) {return reject(response);}
    }
    @Override public void afterCompletion(HttpServletRequest request,HttpServletResponse response,Object handler,Exception error) {
        holder(request).close();
    }
    @Override public void afterConcurrentHandlingStarted(HttpServletRequest request,HttpServletResponse response,Object handler) {
        var holder=holder(request);
        if(!holder.listenerAttached.compareAndSet(false,true))return;
        if(!request.isAsyncStarted()) {holder.close();return;}
        try {request.getAsyncContext().addListener(new AsyncListener() {
            @Override public void onComplete(AsyncEvent event) {holder.close();}
            @Override public void onError(AsyncEvent event) {terminate(event);}
            @Override public void onTimeout(AsyncEvent event) {terminate(event);}
            @Override public void onStartAsync(AsyncEvent event) {
                try {event.getAsyncContext().addListener(this);}
                catch(IllegalStateException alreadyCompleted) {holder.close();}
            }
            private void terminate(AsyncEvent event) {
                try {
                    if(!response.isCommitted())response.setStatus(503);
                    event.getAsyncContext().complete();
                } catch(IllegalStateException alreadyCompleted) {
                    // Servlet completion raced this notification. The response no longer serves.
                } finally {holder.close();}
            }
        });} catch(IllegalStateException alreadyCompleted) {holder.close();}
    }
    private Holder holder(HttpServletRequest request) {
        if(request.getAttribute(ATTRIBUTE) instanceof Holder holder)return holder;
        throw new IllegalStateException("Source serving request lease missing");
    }
    private boolean reject(HttpServletResponse response) throws IOException {
        response.setStatus(503);response.setContentType("application/problem+json");response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"status\":503,\"message\":\"Search is temporarily unavailable.\"}");return false;
    }
    private static final class Holder {
        final SourceVersionReadLeases.Lease lease;
        final AtomicBoolean closed=new AtomicBoolean(),listenerAttached=new AtomicBoolean();
        Holder(SourceVersionReadLeases.Lease lease) {this.lease=lease;}
        void close() {
            if(!closed.compareAndSet(false,true))return;
            try {lease.close();}
            catch(RuntimeException failure) {log.error("Source serving lease cleanup failed; transition remains subject to DB locking",failure);}
        }
    }
}
