//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.core.server.handler;

import org.eclipse.jetty.core.server.Content;
import org.eclipse.jetty.core.server.Handler;
import org.eclipse.jetty.core.server.Request;
import org.eclipse.jetty.core.server.Response;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.StringUtil;

/**
 * Dump request handler.
 * Dumps GET and POST requests.
 * Useful for testing and debugging.
 */
public class EchoHandler extends Handler.Abstract
{
    @Override
    protected void handle(Request request, Response response) throws Exception
    {
        response.setStatus(200);
        HttpFields headers = request.getHttpFields();
        String contentType = headers.get(HttpHeader.CONTENT_TYPE);
        if (StringUtil.isNotBlank(contentType))
            response.setContentType(contentType);
        long contentLength = headers.getLongField(HttpHeader.CONTENT_LENGTH);
        if (contentLength >= 0)
            response.setContentLength(contentLength);
        if (headers.contains(HttpHeader.TRAILER))
            response.getTrailers();
        new Echo(request, response).iterate();
    }

    static class Echo extends IteratingCallback
    {
        private final Request _request;
        private final Response _response;

        Echo(Request request, Response response)
        {
            _request = request;
            _response = response;
        }

        @Override
        protected Action process()
        {
            Content content = _request.readContent();
            if (content == null)
            {
                _request.demandContent(this::succeeded);
                return Action.SCHEDULED;
            }

            if (content instanceof Content.Trailers)
            {
                _response.getTrailers()
                    .add("Echo", "Trailers")
                    .add(((Content.Trailers)content).getTrailers());
                content.release();
                this.succeeded();
                return Action.SCHEDULED;
            }

            if (!content.hasRemaining() && content.isLast())
            {
                content.release();
                return Action.SUCCEEDED;
            }

            _response.write(content.isLast(), Callback.from(this, content::release), content.getByteBuffer());
            return Action.SCHEDULED;
        }

        @Override
        protected void onCompleteSuccess()
        {
            _request.succeeded();
        }

        @Override
        protected void onCompleteFailure(Throwable cause)
        {
            _request.failed(cause);
        }
    }
}
