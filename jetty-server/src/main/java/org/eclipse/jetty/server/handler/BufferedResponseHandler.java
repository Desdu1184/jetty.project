//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server.handler;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.pathmap.PathSpecSet;
import org.eclipse.jetty.io.ByteBufferAccumulator;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IncludeExclude;
import org.eclipse.jetty.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>
 * A Handler that can apply a
 * mechanism to buffer the entire response content until the output is closed.
 * This allows the commit to be delayed until the response is complete and thus
 * headers and response status can be changed while writing the body.
 * </p>
 * <p>
 * Note that the decision to buffer is influenced by the headers and status at the
 * first write, and thus subsequent changes to those headers will not influence the
 * decision to buffer or not.
 * </p>
 * <p>
 * Note also that there are no memory limits to the size of the buffer, thus
 * this handler can represent an unbounded memory commitment if the content
 * generated can also be unbounded.
 * </p>
 */
public class BufferedResponseHandler extends HandlerWrapper
{
    private static final Logger LOG = LoggerFactory.getLogger(BufferedResponseHandler.class);

    private final IncludeExclude<String> _methods = new IncludeExclude<>();
    private final IncludeExclude<String> _paths = new IncludeExclude<>(PathSpecSet.class);
    private final IncludeExclude<String> _mimeTypes = new IncludeExclude<>();

    public BufferedResponseHandler()
    {
        _methods.include(HttpMethod.GET.asString());
        for (String type : MimeTypes.getKnownMimeTypes())
        {
            if (type.startsWith("image/") ||
                type.startsWith("audio/") ||
                type.startsWith("video/"))
                _mimeTypes.exclude(type);
        }

        if (LOG.isDebugEnabled())
            LOG.debug("{} mime types {}", this, _mimeTypes);
    }

    public IncludeExclude<String> getMethodIncludeExclude()
    {
        return _methods;
    }

    public IncludeExclude<String> getPathIncludeExclude()
    {
        return _paths;
    }

    public IncludeExclude<String> getMimeIncludeExclude()
    {
        return _mimeTypes;
    }

    protected boolean isMimeTypeBufferable(String mimetype)
    {
        return _mimeTypes.test(mimetype);
    }

    protected boolean isPathBufferable(String requestURI)
    {
        if (requestURI == null)
            return true;

        return _paths.test(requestURI);
    }

    protected boolean shouldBuffer(Response response, boolean last)
    {
        if (last)
            return false;

        int status = response.getStatus();
        if (HttpStatus.hasNoBody(status) || HttpStatus.isRedirection(status))
            return false;

        String ct = response.getHeaders().get(HttpHeader.CONTENT_TYPE);
        if (ct == null)
            return true;

        ct = MimeTypes.getContentTypeWithoutCharset(ct);
        return isMimeTypeBufferable(StringUtil.asciiToLowerCase(ct));
    }

    @Override
    public Request.Processor handle(Request request) throws Exception
    {
        Request.Processor processor = super.handle(request);
        if (processor == null)
            return null;

        final String path = request.getPathInContext();

        if (LOG.isDebugEnabled())
            LOG.debug("{} handle {} in {}", this, request, request.getContext());

        // If not a supported method this URI is always excluded.
        if (!_methods.test(request.getMethod()))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} excluded by method {}", this, request);
            return processor;
        }

        // If not a supported path this URI is always excluded.
        if (!isPathBufferable(path))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} excluded by path {}", this, request);
            return processor;
        }

        // If the mime type is known from the path then apply mime type filtering.
        String mimeType = MimeTypes.getDefaultMimeByExtension(path); // TODO context specicif mimetypes : context.getMimeType(path);
        if (mimeType != null)
        {
            mimeType = MimeTypes.getContentTypeWithoutCharset(mimeType);
            if (!isMimeTypeBufferable(mimeType))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} excluded by path suffix mime type {}", this, request);

                // handle normally without setting vary header
                return processor;
            }
        }

        // Install buffered interceptor and handle.
        return new Request.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback) throws Exception
            {
                BufferedResponse bufferedResponse = new BufferedResponse(response, callback);
                processor.process(request, bufferedResponse, bufferedResponse);
            }
        };
    }

    private class BufferedResponse extends Response.Wrapper implements Callback
    {
        private final Callback _callback;
        private ByteBufferAccumulator _accumulator;

        BufferedResponse(Response response, Callback callback)
        {
            super(response);
            _callback = callback;
        }

        @Override
        public void write(boolean last, Callback callback, ByteBuffer... buffer)
        {
            if (shouldBuffer(this, last))
            {
                // TODO check on first write if mimetype is OK
            }
            // TODO allocate the accumulator
            // TODO enforce size limits?
            // TODO handle last
            for (ByteBuffer b : buffer)
                _accumulator.copyBuffer(b);
            callback.succeeded(); // TODO infinite recursion?
        }

        @Override
        public void succeeded()
        {
            super.write(true, Callback.from(_callback, _accumulator::close), _accumulator.takeByteBuffer());
        }

        @Override
        public void failed(Throwable x)
        {
            _accumulator.close();
        }
    }

}
