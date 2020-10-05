package io.quarkus.rest.runtime.jaxrs;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.GenericEntity;
import javax.ws.rs.core.Link;
import javax.ws.rs.core.Link.Builder;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.NewCookie;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.StatusType;

import io.quarkus.rest.runtime.core.QuarkusRestRequestContext;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.buffer.impl.BufferImpl;

public class QuarkusRestContainerResponseContext implements ContainerResponseContext {

    private final QuarkusRestRequestContext context;

    public QuarkusRestContainerResponseContext(QuarkusRestRequestContext requestContext) {
        this.context = requestContext;
    }

    @Override
    public int getStatus() {
        return context.getResponse().getStatus();
    }

    @Override
    public void setStatus(int code) {
        context.setResult(Response.fromResponse(context.getResponse()).status(code).build());
    }

    @Override
    public StatusType getStatusInfo() {
        return context.getResponse().getStatusInfo();
    }

    @Override
    public void setStatusInfo(StatusType statusInfo) {
        context.setResult(Response.fromResponse(context.getResponse()).status(statusInfo).build());
    }

    @Override
    public MultivaluedMap<String, Object> getHeaders() {
        return context.getResponse().getHeaders();
    }

    @Override
    public MultivaluedMap<String, String> getStringHeaders() {
        return context.getResponse().getStringHeaders();
    }

    @Override
    public String getHeaderString(String name) {
        return context.getResponse().getHeaderString(name);
    }

    @Override
    public Set<String> getAllowedMethods() {
        return context.getResponse().getAllowedMethods();
    }

    @Override
    public Date getDate() {
        return context.getResponse().getDate();
    }

    @Override
    public Locale getLanguage() {
        return context.getResponse().getLanguage();
    }

    @Override
    public int getLength() {
        return context.getResponse().getLength();
    }

    @Override
    public MediaType getMediaType() {
        return context.getResponse().getMediaType();
    }

    @Override
    public Map<String, NewCookie> getCookies() {
        return context.getResponse().getCookies();
    }

    @Override
    public EntityTag getEntityTag() {
        return context.getResponse().getEntityTag();
    }

    @Override
    public Date getLastModified() {
        return context.getResponse().getLastModified();
    }

    @Override
    public URI getLocation() {
        return context.getResponse().getLocation();
    }

    @Override
    public Set<Link> getLinks() {
        return context.getResponse().getLinks();
    }

    @Override
    public boolean hasLink(String relation) {
        return context.getResponse().hasLink(relation);
    }

    @Override
    public Link getLink(String relation) {
        return context.getResponse().getLink(relation);
    }

    @Override
    public Builder getLinkBuilder(String relation) {
        return context.getResponse().getLinkBuilder(relation);
    }

    @Override
    public boolean hasEntity() {
        return context.getResponse().hasEntity();
    }

    @Override
    public Object getEntity() {
        return context.getResponse().getEntity();
    }

    @Override
    public Class<?> getEntityClass() {
        Object entity = getEntity();
        if (entity == null) {
            return null;
        }
        return entity.getClass();
    }

    @Override
    public Type getEntityType() {
        return context.getGenericReturnType();
    }

    @Override
    public void setEntity(Object entity) {
        context.resetBuildTimeSerialization();
        if (entity instanceof GenericEntity) {
            context.setGenericReturnType(((GenericEntity<?>) entity).getType());
            entity = ((GenericEntity<?>) entity).getEntity();
        }
        Response.ResponseBuilder resp = Response.fromResponse(context.getResponse()).entity(entity);
        context.setResult(resp.build());
    }

    @Override
    public void setEntity(Object entity, Annotation[] annotations, MediaType mediaType) {
        context.resetBuildTimeSerialization();
        if (entity instanceof GenericEntity) {
            context.setGenericReturnType(((GenericEntity<?>) entity).getType());
            entity = ((GenericEntity<?>) entity).getEntity();
        }
        context.setProducesMediaType(mediaType);
        context.setAllAnnotations(annotations);
        Response.ResponseBuilder resp = Response.fromResponse(context.getResponse()).entity(entity).type(mediaType);
        context.setResult(resp.build());
    }

    @Override
    public Annotation[] getEntityAnnotations() {
        return context.getAllAnnotations();
    }

    @Override
    public OutputStream getEntityStream() {
        return new OutputStream() {
            private final Buffer buffer = new BufferImpl();

            @Override
            public void write(byte[] b) {
                buffer.appendBytes(b);
            }

            @Override
            public void write(int b) {
                buffer.appendInt(b);
            }

            @Override
            public void close() throws IOException {
                // the way we handle things, it doesn't make sense for this to be anything but a String
                setEntity(new String(buffer.getBytes()));
            }
        };
    }

    @Override
    public void setEntityStream(OutputStream outputStream) {
        throw new UnsupportedOperationException();
    }

}
