package io.kreteg.core.web;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

/**
 * Serves the UI's {@code index.html} for page URLs such as {@code /conversations/abc}, so the single-page app's
 * routes survive a reload or a pasted link. Runs after every other route, and only for browser page requests:
 * API, MCP and asset paths that match nothing still get a 404.
 */
@ApplicationScoped
public class SpaFallback {

    private static final List<String> SERVER_PREFIXES = List.of("/api/", "/mcp", "/q/");

    void install(@Observes Router router) {
        router.get().order(Integer.MAX_VALUE).handler(this::handle);
    }

    private void handle(RoutingContext ctx) {
        if (isPageRequest(ctx)) {
            ctx.reroute("/");
        } else {
            ctx.next();
        }
    }

    private static boolean isPageRequest(RoutingContext ctx) {
        String path = ctx.normalizedPath();
        String accept = ctx.request().getHeader(HttpHeaders.ACCEPT);
        return !path.equals("/")
                && SERVER_PREFIXES.stream().noneMatch(path::startsWith)
                && !path.substring(path.lastIndexOf('/')).contains(".")
                && accept != null && accept.contains("text/html");
    }
}
