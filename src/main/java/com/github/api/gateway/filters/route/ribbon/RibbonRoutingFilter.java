package com.github.api.gateway.filters.route.ribbon;

import com.github.api.gateway.filters.route.ProxyRequestHelper;
import com.netflix.client.ClientException;
import com.netflix.hystrix.exception.HystrixRuntimeException;
import com.netflix.zuul.ZuulFilter;
import com.netflix.zuul.context.RequestContext;
import com.netflix.zuul.exception.ZuulException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.MultiValueMap;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * Created by yifanzhang.
 */
public class RibbonRoutingFilter extends ZuulFilter {
    private static final Logger log = LoggerFactory.getLogger(RibbonRoutingFilter.class);
    protected ProxyRequestHelper helper;
    protected RibbonCommandFactory<?> ribbonCommandFactory;

    public RibbonRoutingFilter(ProxyRequestHelper helper,
        RibbonCommandFactory<?> ribbonCommandFactory) {
        this.helper = helper;
        this.ribbonCommandFactory = ribbonCommandFactory;
    }

    public RibbonRoutingFilter(RibbonCommandFactory<?> ribbonCommandFactory) {
        this(new ProxyRequestHelper(),ribbonCommandFactory);
    }

    @Override
    public String filterType() {
        return "route";
    }

    @Override
    public int filterOrder() {
        return 10;
    }

    @Override
    public boolean shouldFilter() {
        RequestContext ctx = RequestContext.getCurrentContext();
        /**
         * serviceId is a physical URL or
         * a service, but can't be both.
         */
        return (ctx.getRouteHost() == null && ctx.get("serviceId") != null
            && ctx.sendZuulResponse());
    }

    @Override
    public Object run() {
        RequestContext context = RequestContext.getCurrentContext();
        this.helper.addIgnoredHeaders();
        try {
            RibbonCommandContext commandContext = buildCommandContext(context);
            ClientHttpResponse response = forward(commandContext);
            setResponse(response);
            return response;
        } catch (ZuulException ex) {
            context.set("error.status_code", ex.nStatusCode);
            context.set("error.message", ex.errorCause);
            context.set("error.exception", ex);
        } catch (Exception ex) {
            context.set("error.status_code",
                HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            context.set("error.exception", ex);
        }
        return null;
    }

    protected RibbonCommandContext buildCommandContext(RequestContext context) {
        HttpServletRequest request = context.getRequest();

        MultiValueMap<String,String> headers = this.helper
            .buildZuulRequestHeaders(request);
        MultiValueMap<String,String> params = this.helper
            .buildZuulRequestQueryParams(request);
        String verb = getVerb(request);
        InputStream requestEntity = getRequestBody(request);
        if (request.getContentLength() < 0) {
            context.setChunkedRequestBody();
        }

        String serviceId = (String) context.get("serviceId");
        Boolean retryable = (Boolean)context.get("retryable");

        String uri = this.helper.buildZuulRequestURI(request);

        // remove double slashes
        return new RibbonCommandContext(serviceId,verb,uri,retryable,
            headers,params,requestEntity);
    }

    protected ClientHttpResponse forward(RibbonCommandContext context) throws Exception {
        Map<String, Object> info = this.helper.debug(context.getVerb(), context.getUri(),
            context.getHeaders(), context.getParams(), context.getRequestEntity());

        RibbonCommand command = this.ribbonCommandFactory.create(context);
        try {
            ClientHttpResponse response = command.execute();
            this.helper.appendDebug(info, response.getStatusCode().value(),
                response.getHeaders());
            return response;
        }
        catch (HystrixRuntimeException ex) {
            return handleException(info, ex);
        }

    }

    protected ClientHttpResponse handleException(Map<String, Object> info,
        HystrixRuntimeException ex) throws ZuulException {
        int statusCode = HttpStatus.INTERNAL_SERVER_ERROR.value();
        Throwable cause = ex;
        String message = ex.getFailureType().toString();

        ClientException clientException = findClientException(ex);
        if (clientException == null) {
            clientException = findClientException(ex.getFallbackException());
        }

        if (clientException != null) {
            if (clientException.getErrorType() ==
                ClientException.ErrorType.SERVER_THROTTLED) {
                statusCode = HttpStatus.SERVICE_UNAVAILABLE.value();
            }
            cause = clientException;
            message = clientException.getErrorType().toString();
        }
        info.put("status", String.valueOf(statusCode));
        log.error("Forwarding error: statusCode = "+statusCode+", error = "+message);
        if (log.isDebugEnabled()) {
            log.debug(info.toString());
        }
        throw new ZuulException(cause, "Forwarding error", statusCode, message);
    }

    protected ClientException findClientException(Throwable t) {
        if (t == null) {
            return null;
        }
        if (t instanceof ClientException) {
            return (ClientException) t;
        }
        return findClientException(t.getCause());
    }

    protected String getVerb(HttpServletRequest request) {
        String method = request.getMethod();
        if (method == null) {
            return "GET";
        }
        return method;
    }

    protected void setResponse(ClientHttpResponse resp)
        throws ClientException, IOException {
        this.helper.setResponse(resp.getStatusCode().value(),
            resp.getBody() == null ? null : resp.getBody(), resp.getHeaders());
    }

    protected InputStream getRequestBody(HttpServletRequest request) {
        InputStream requestEntity = null;
        if (request.getMethod().equals("DELETE")) {
            return null;
        }

        try {
            requestEntity = (InputStream) RequestContext.getCurrentContext()
                .get("requestEntity");
            if (requestEntity == null) {
                requestEntity = request.getInputStream();
            }
        } catch (IOException e) {
            log.error("Error during getRequestBody", e);
        }
        return requestEntity;
    }
}
