package org.jboss.resteasy.reactive.server.jaxrs;

import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import javax.ws.rs.sse.OutboundSseEvent;
import javax.ws.rs.sse.SseEventSink;
import org.jboss.resteasy.reactive.common.http.ServerHttpResponse;
import org.jboss.resteasy.reactive.server.core.ResteasyReactiveRequestContext;
import org.jboss.resteasy.reactive.server.core.SseUtil;

public class QuarkusRestSseEventSink implements SseEventSink {

    private static final byte[] EMPTY_BUFFER = new byte[0];
    private ResteasyReactiveRequestContext context;
    private QuarkusRestSseBroadcasterImpl broadcaster;

    public QuarkusRestSseEventSink(ResteasyReactiveRequestContext context) {
        this.context = context;
    }

    @Override
    public boolean isClosed() {
        return context.serverResponse().closed();
    }

    @Override
    public CompletionStage<?> send(OutboundSseEvent event) {
        if (isClosed())
            throw new IllegalStateException("Already closed");
        // NOTE: we can't cast event to QuarkusRestOutboundSseEvent because the TCK sends us its own subclass
        CompletionStage<?> ret = SseUtil.send(context, event);
        if (broadcaster != null) {
            return ret.whenComplete((value, x) -> {
                if (x != null) {
                    broadcaster.fireException(this, x);
                }
            });
        }
        return ret;
    }

    @Override
    public void close() {
        if (isClosed())
            return;
        // FIXME: do we need a state flag?
        ServerHttpResponse response = context.serverResponse();
        response.end();
        context.close();
        if (broadcaster != null)
            broadcaster.fireClose(this);
    }

    public void sendInitialResponse(ServerHttpResponse response) {
        if (!response.headWritten()) {
            SseUtil.setHeaders(context, response);
            // send the headers over the wire
            context.suspend();
            response.write(EMPTY_BUFFER, new Consumer<Throwable>() {
                @Override
                public void accept(Throwable throwable) {
                    if (throwable == null) {
                        context.resume();
                    } else {
                        context.resume(throwable);
                    }
                    // I don't think we should be firing the exception on the broadcaster here
                }
            });
            //            response.closeHandler(v -> {
            //                // FIXME: notify of client closing
            //                System.err.println("Server connection closed");
            //            });
        }
    }

    void register(QuarkusRestSseBroadcasterImpl broadcaster) {
        if (this.broadcaster != null)
            throw new IllegalStateException("Already registered on a broadcaster");
        this.broadcaster = broadcaster;
    }
}