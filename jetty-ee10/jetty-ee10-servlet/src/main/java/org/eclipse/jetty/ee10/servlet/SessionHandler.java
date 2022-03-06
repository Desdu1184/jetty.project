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

package org.eclipse.jetty.ee10.servlet;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.EventListener;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletContext;
import jakarta.servlet.SessionCookieConfig;
import jakarta.servlet.SessionTrackingMode;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionActivationListener;
import jakarta.servlet.http.HttpSessionAttributeListener;
import jakarta.servlet.http.HttpSessionBindingEvent;
import jakarta.servlet.http.HttpSessionBindingListener;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionIdListener;
import jakarta.servlet.http.HttpSessionListener;
import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http.Syntax;
import org.eclipse.jetty.server.HttpStream;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.session.AbstractSessionHandler;
import org.eclipse.jetty.session.Session;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SessionHandler extends AbstractSessionHandler
{    
    static final Logger LOG = LoggerFactory.getLogger(SessionHandler.class);
    
    public static final EnumSet<SessionTrackingMode> DEFAULT_TRACKING = 
        EnumSet.of(SessionTrackingMode.COOKIE, SessionTrackingMode.URL);
    
    public static final Set<SessionTrackingMode> DEFAULT_SESSION_TRACKING_MODES =
        Collections.unmodifiableSet(
            new HashSet<>(
                Arrays.asList(SessionTrackingMode.COOKIE, SessionTrackingMode.URL)));

    @SuppressWarnings("unchecked")
    public static final Class<? extends EventListener>[] SESSION_LISTENER_TYPES =
        new Class[]
            {
                HttpSessionAttributeListener.class,
                HttpSessionIdListener.class,
                HttpSessionListener.class
            };
    
    private final List<HttpSessionAttributeListener> _sessionAttributeListeners = new CopyOnWriteArrayList<>();
    private final List<HttpSessionListener> _sessionListeners = new CopyOnWriteArrayList<>();
    private final List<HttpSessionIdListener> _sessionIdListeners = new CopyOnWriteArrayList<>();

    private Set<SessionTrackingMode> _sessionTrackingModes;
    private SessionCookieConfig _cookieConfig = new CookieConfig();
    private ServletContextHandler.Context _servletContextHandlerContext;
   
    /**
     * CookieConfig
     *
     * Implementation of the jakarta.servlet.SessionCookieConfig.
     * SameSite configuration can be achieved by using setComment
     *
     * @see HttpCookie
     */
    public final class CookieConfig implements SessionCookieConfig
    {
        private Map<String, String> _attributes = new HashMap<>();

        @Override
        public String getComment()
        {
            return _sessionComment;
        }

        @Override
        public String getDomain()
        {
            return _sessionDomain;
        }

        @Override
        public int getMaxAge()
        {
            return _maxCookieAge;
        }

        @Override
        public void setAttribute(String name, String value)
        {
            _attributes.put(name, value);
        }

        @Override
        public String getAttribute(String name)
        {
            return _attributes.get(name);
        }

        @Override
        public Map<String, String> getAttributes()
        {
            return Collections.unmodifiableMap(_attributes);
        }

        @Override
        public String getName()
        {
            return _sessionCookie;
        }

        @Override
        public String getPath()
        {
            return _sessionPath;
        }

        @Override
        public boolean isHttpOnly()
        {
            return _httpOnly;
        }

        @Override
        public boolean isSecure()
        {
            return _secureCookies;
        }

        @Override
        public void setComment(String comment)
        {
            if (_servletContextHandlerContext != null && _servletContextHandlerContext.getServletContextHandler().isAvailable())
                throw new IllegalStateException("CookieConfig cannot be set after ServletContext is started");
            _sessionComment = comment;
        }

        @Override
        public void setDomain(String domain)
        {
            if (_servletContextHandlerContext != null && _servletContextHandlerContext.getServletContextHandler().isAvailable())
                throw new IllegalStateException("CookieConfig cannot be set after ServletContext is started");
            _sessionDomain = domain;
        }

        @Override
        public void setHttpOnly(boolean httpOnly)
        {
            if (_servletContextHandlerContext != null && _servletContextHandlerContext.getServletContextHandler().isAvailable())
                throw new IllegalStateException("CookieConfig cannot be set after ServletContext is started");
            _httpOnly = httpOnly;
        }

        @Override
        public void setMaxAge(int maxAge)
        {
            if (_servletContextHandlerContext != null && _servletContextHandlerContext.getServletContextHandler().isAvailable())
                throw new IllegalStateException("CookieConfig cannot be set after ServletContext is started");
            _maxCookieAge = maxAge;
        }

        @Override
        public void setName(String name)
        {
            if (_servletContextHandlerContext != null && _servletContextHandlerContext.getServletContextHandler().isAvailable())
                throw new IllegalStateException("CookieConfig cannot be set after ServletContext is started");
            if ("".equals(name))
                throw new IllegalArgumentException("Blank cookie name");
            if (name != null)
                Syntax.requireValidRFC2616Token(name, "Bad Session cookie name");
            _sessionCookie = name;
        }

        @Override
        public void setPath(String path)
        {
            if (_servletContextHandlerContext != null && _servletContextHandlerContext.getServletContextHandler().isAvailable())
                throw new IllegalStateException("CookieConfig cannot be set after ServletContext is started");
            _sessionPath = path;
        }

        @Override
        public void setSecure(boolean secure)
        {
            if (_servletContextHandlerContext != null && _servletContextHandlerContext.getServletContextHandler().isAvailable())
                throw new IllegalStateException("CookieConfig cannot be set after ServletContext is started");
            _secureCookies = secure;
        }
    }
    
    public static class ServletAPISession implements HttpSession, Session.APISession
    {
        public static ServletAPISession wrapSession(Session session)
        {
            ServletAPISession apiSession = new ServletAPISession(session);
            session.setAPISessin(apiSession);
            return apiSession;
        }
        
        private final Session _session;
        
        private ServletAPISession(Session session)
        {
            _session = session;           
        }

        @Override
        public Session getSession()
        {
            return _session;
        }

        @Override
        public long getCreationTime()
        {
            return _session.getCreationTime();
        }

        @Override
        public String getId()
        {
            return _session.getId();
        }

        @Override
        public long getLastAccessedTime()
        {
            return _session.getLastAccessedTime();
        }

        @Override
        public ServletContext getServletContext()
        {
            return ServletContextHandler.getServletContext((ContextHandler.ScopedContext)_session.getSessionManager().getContext());
        }

        @Override
        public void setMaxInactiveInterval(int interval)
        {
            _session.setMaxInactiveInterval(interval);
        }

        @Override
        public int getMaxInactiveInterval()
        {
            return _session.getMaxInactiveInterval();
        }

        @Override
        public Object getAttribute(String name)
        {
            return _session.getAttribute(name);
        }

        @Override
        public Enumeration<String> getAttributeNames()
        {
            final Iterator<String> itor = _session.getNames().iterator();
            return new Enumeration<>()
            {

                @Override
                public boolean hasMoreElements()
                {
                    return itor.hasNext();
                }

                @Override
                public String nextElement()
                {
                    return itor.next();
                }
            };
        }

        @Override
        public void setAttribute(String name, Object value)
        {
            _session.setAttribute(name, value);
        }

        @Override
        public void removeAttribute(String name)
        {
            _session.removeAttribute(name);
        }

        @Override
        public void invalidate()
        {
            _session.invalidate();
        }

        @Override
        public boolean isNew()
        {
            return _session.isNew();
        }
    }

    public SessionHandler()
    {
        setSessionTrackingModes(DEFAULT_SESSION_TRACKING_MODES);
    }

    /**
     * Adds an event listener for session-related events.
     *
     * @param listener the session event listener to add
     * Individual SessionManagers implementations may accept arbitrary listener types,
     * but they are expected to at least handle HttpSessionActivationListener,
     * HttpSessionAttributeListener, HttpSessionBindingListener and HttpSessionListener.
     * @return true if the listener was added
     * @see #removeEventListener(EventListener)
     * @see HttpSessionAttributeListener
     * @see HttpSessionListener
     * @see HttpSessionIdListener
     */
    @Override
    public boolean addEventListener(EventListener listener)
    {
        if (super.addEventListener(listener))
        {
            if (listener instanceof HttpSessionAttributeListener)
                _sessionAttributeListeners.add((HttpSessionAttributeListener)listener);
            if (listener instanceof HttpSessionListener)
                _sessionListeners.add((HttpSessionListener)listener);
            if (listener instanceof HttpSessionIdListener)
                _sessionIdListeners.add((HttpSessionIdListener)listener);
            return true;
        }
        return false;
    }    

    @Override
    public boolean removeEventListener(EventListener listener)
    {
        if (super.removeEventListener(listener))
        {
            if (listener instanceof HttpSessionAttributeListener)
                _sessionAttributeListeners.remove(listener);
            if (listener instanceof HttpSessionListener)
                _sessionListeners.remove(listener);
            if (listener instanceof HttpSessionIdListener)
                _sessionIdListeners.remove(listener);
            return true;
        }
        return false;
    }
    
    @Override
    public void doStart() throws Exception
    {

        super.doStart();
        if (!(_context instanceof ServletContextHandler.Context))
            throw new IllegalStateException("!ServlerContextHandler.Context");
        _servletContextHandlerContext = (ServletContextHandler.Context)_context;
    }

    /**
     * Set up cookie configuration based on init params, if
     * the SessionCookieConfig has not been set.
     */
    protected void configureCookies()
    {
        // Look for a session cookie name
        if (_servletContextHandlerContext != null)
        {
            String tmp = _servletContextHandlerContext.getServletContext().getInitParameter(__SessionCookieProperty);
            if (tmp != null)
                _sessionCookie = tmp;

            tmp = _servletContextHandlerContext.getServletContext().getInitParameter(__SessionIdPathParameterNameProperty);
            if (tmp != null)
                setSessionIdPathParameterName(tmp);

            // set up the max session cookie age if it isn't already
            if (_maxCookieAge == -1)
            {
                tmp = _servletContextHandlerContext.getServletContext().getInitParameter(__MaxAgeProperty);
                if (tmp != null)
                    _maxCookieAge = Integer.parseInt(tmp.trim());
            }

            // set up the session domain if it isn't already
            if (_sessionDomain == null)
                _sessionDomain = _servletContextHandlerContext.getServletContext().getInitParameter(__SessionDomainProperty);

            // set up the sessionPath if it isn't already
            if (_sessionPath == null)
                _sessionPath = _servletContextHandlerContext.getServletContext().getInitParameter(__SessionPathProperty);

            tmp = _servletContextHandlerContext.getServletContext().getInitParameter(__CheckRemoteSessionEncoding);
            if (tmp != null)
                _checkingRemoteSessionIdEncoding = Boolean.parseBoolean(tmp);
        }
    }

    public Session.APISession newSessionAPIWrapper(Session session)
    {
        return ServletAPISession.wrapSession(session);
    }

    @Override
    public void callSessionAttributeListeners(Session session, String name, Object old, Object value)
    {
        if (!_sessionAttributeListeners.isEmpty())
        {
            HttpSessionBindingEvent event = new HttpSessionBindingEvent(session.getAPISession(), name, old == null ? value : old);

            for (HttpSessionAttributeListener l : _sessionAttributeListeners)
            {
                if (old == null)
                    l.attributeAdded(event);
                else if (value == null)
                    l.attributeRemoved(event);
                else
                    l.attributeReplaced(event);
            }
        }
    }
    
    /**
     * Call the session lifecycle listeners in the order
     * they were added.
     *
     * @param session the session on which to call the lifecycle listeners
     */
    @Override
    public void callSessionCreatedListeners(Session session)
    {
        if (session == null)
            return;

        if (_sessionListeners != null)
        {
            HttpSessionEvent event = new HttpSessionEvent(session.getAPISession());
            for (HttpSessionListener  l : _sessionListeners)
            {
                l.sessionCreated(event);
            }
        }
    }
 
    /**
     * Call the session lifecycle listeners in
     * the reverse order they were added.
     *
     * @param session the session on which to call the lifecycle listeners
     */
    @Override
    public void callSessionDestroyedListeners(Session session)
    {
        if (session == null)
            return;

        if (_sessionListeners != null)
        {
            //We annoint the calling thread with
            //the webapp's classloader because the calling thread may
            //come from the scavenger, rather than a request thread
            Runnable r = new Runnable()
            {
                @Override
                public void run()
                {
                    HttpSessionEvent event = new HttpSessionEvent(session.getAPISession());
                    for (int i = _sessionListeners.size() - 1; i >= 0; i--)
                    {
                        _sessionListeners.get(i).sessionDestroyed(event);
                    }
                }
            };
            _sessionContext.run(r);
        }
    }

    @Override
    public void callSessionIdListeners(Session session, String oldId)
    {
        //inform the listeners
        if (!_sessionIdListeners.isEmpty())
        {
            HttpSessionEvent event = new HttpSessionEvent(session.getAPISession());
            for (HttpSessionIdListener l : _sessionIdListeners)
            {
                l.sessionIdChanged(event, oldId);
            }
        }
    }
    
    @Override
    public void callUnboundBindingListener(Session session, String name, Object value)
    {
        if (value instanceof HttpSessionBindingListener)
            ((HttpSessionBindingListener)value).valueUnbound(new HttpSessionBindingEvent(session.getAPISession(), name));
    }
    
    @Override
    public void callBoundBindingListener(Session session, String name, Object value)
    {
        if (value instanceof HttpSessionBindingListener)
            ((HttpSessionBindingListener)value).valueBound(new HttpSessionBindingEvent(session.getAPISession(), name)); 
    }
    
    @Override
    public void callSessionActivationListener(Session session, String name, Object value)
    {
        if (value instanceof HttpSessionActivationListener)
        {
            HttpSessionEvent event = new HttpSessionEvent(session.getAPISession());
            HttpSessionActivationListener listener = (HttpSessionActivationListener)value;
            listener.sessionDidActivate(event);
        }
        
    }

    @Override
    public void callSessionPassivationListener(Session session, String name, Object value)
    {
        if (value instanceof HttpSessionActivationListener)
        {
            HttpSessionEvent event = new HttpSessionEvent(session.getAPISession());
            HttpSessionActivationListener listener = (HttpSessionActivationListener)value;
            listener.sessionWillPassivate(event);
        }
    }
    
    public SessionCookieConfig getSessionCookieConfig()
    {
        return _cookieConfig;
    }
    
    public Set<SessionTrackingMode> getDefaultSessionTrackingModes()
    {
        return DEFAULT_SESSION_TRACKING_MODES;
    }

    public Set<SessionTrackingMode> getEffectiveSessionTrackingModes()
    {
        return Collections.unmodifiableSet(_sessionTrackingModes);
    }

    public void setSessionTrackingModes(Set<SessionTrackingMode> sessionTrackingModes)
    {
        if (sessionTrackingModes != null &&
            sessionTrackingModes.size() > 1 &&
            sessionTrackingModes.contains(SessionTrackingMode.SSL))
        {
            throw new IllegalArgumentException("sessionTrackingModes specifies a combination of SessionTrackingMode.SSL with a session tracking mode other than SessionTrackingMode.SSL");
        }
        _sessionTrackingModes = new HashSet<>(sessionTrackingModes);
        _usingCookies = _sessionTrackingModes.contains(SessionTrackingMode.COOKIE);
        _usingURLs = _sessionTrackingModes.contains(SessionTrackingMode.URL);
    } 
    
    /**
     * Look for a requested session ID in cookies and URI parameters
     *
     * @param request the request to check
     */
    protected void resolveRequestedSessionId(ServletScopedRequest.MutableHttpServletRequest request)
    {
        String requestedSessionId = request.getRequestedSessionId();

        if (requestedSessionId != null)
        {
            Session session = getSession(requestedSessionId);
            if (session != null && session.isValid())
            {
                request.setSession(session);
            }
            return;
        }
        else if (!DispatcherType.REQUEST.equals(request.getDispatcherType()))
            return;

        boolean requestedSessionIdFromCookie = false;
        Session session = null;

        //first try getting id from a cookie
        if (isUsingCookies())
        {
            Cookie[] cookies = request.getCookies();
            if (cookies != null && cookies.length > 0)
            {
                final String sessionCookie = getSessionCookie();
                for (Cookie cookie : cookies)
                {
                    if (sessionCookie.equalsIgnoreCase(cookie.getName()))
                    {
                        String id = cookie.getValue();
                        requestedSessionIdFromCookie = true;
                        if (LOG.isDebugEnabled())
                            LOG.debug("Got Session ID {} from cookie {}", id, sessionCookie);

                        if (session == null)
                        {
                            //we currently do not have a session selected, use this one if it is valid
                            Session s = getSession(id);
                            if (s != null && s.isValid())
                            {
                                //associate it with the request so its reference count is decremented as the
                                //request exits
                                requestedSessionId = id;
                                session = s;
                                request.setSession(session);

                                if (LOG.isDebugEnabled())
                                    LOG.debug("Selected session {}", session);
                            }
                            else
                            {
                                if (LOG.isDebugEnabled())
                                    LOG.debug("No session found for session cookie id {}", id);

                                //if we don't have a valid session id yet, just choose the current id
                                if (requestedSessionId == null)
                                    requestedSessionId = id;
                            }
                        }
                        else
                        {
                            //we currently have a valid session selected. We will throw an error
                            //if there is a _different_ valid session id cookie. Duplicate ids, or
                            //invalid session ids are ignored
                            if (!session.getId().equals(getSessionIdManager().getId(id)))
                            {
                                if (LOG.isDebugEnabled())
                                    LOG.debug("Multiple different valid session ids: {}, {}", requestedSessionId, id);
                                
                                //load the session to see if it is valid or not
                                Session s = getSession(id);
                                if (s != null && s.isValid())
                                {
                                    //TODO release the session straight away??
                                    try
                                    {
                                        _sessionCache.release(id, s);
                                    }
                                    catch (Exception x)
                                    {
                                        if (LOG.isDebugEnabled())
                                            LOG.debug("Error releasing duplicate valid session: {}", id);
                                    }

                                    throw new BadMessageException("Duplicate valid session cookies: " + requestedSessionId + " ," + id);
                                }
                            }
                            else
                            {
                                if (LOG.isDebugEnabled())
                                    LOG.debug("Duplicate valid session cookie id: {}", id);
                            }
                        }
                    }
                }
            }
        }

        //try getting id from a url
        if (isUsingURLs() && (requestedSessionId == null))
        {
            String uri = request.getRequestURI();
            String prefix = getSessionIdPathParameterNamePrefix();
            if (prefix != null)
            {
                int s = uri.indexOf(prefix);
                if (s >= 0)
                {
                    s += prefix.length();
                    int i = s;
                    while (i < uri.length())
                    {
                        char c = uri.charAt(i);
                        if (c == ';' || c == '#' || c == '?' || c == '/')
                            break;
                        i++;
                    }

                    requestedSessionId = uri.substring(s, i);
                    requestedSessionIdFromCookie = false;

                    if (LOG.isDebugEnabled())
                        LOG.debug("Got Session ID {} from URL", requestedSessionId);

                    session = getSession(requestedSessionId);
                    if (session != null && session.isValid())
                    {
                        request.setSession(session);  //associate the session with the request
                    }
                }
            }
        }

        request.setRequestedSessionId(requestedSessionId);
        request.setRequestedSessionIdFromCookie(requestedSessionId != null && requestedSessionIdFromCookie);
    }

    @Override
    public Request.Processor handle(Request request) throws Exception
    {
        ServletScopedRequest servletScopedRequest = Request.as(request, ServletScopedRequest.class);
        ServletScopedRequest.MutableHttpServletRequest mutableServletRequest = 
            (servletScopedRequest == null ? null : servletScopedRequest.getMutableHttpServletRequest());


        //TODO if not the correct type of request, pass it on to our wrapped handlers?
        if (mutableServletRequest == null)
            throw new IllegalStateException("Request is not a MutableHttpServletRequest");

        mutableServletRequest.setSessionManager(this);
        mutableServletRequest.setSession(null);

        // find and set the session if one exists
        resolveRequestedSessionId(mutableServletRequest);

        ServletScopedResponse servletScopedResponse = servletScopedRequest.getResponse();
        ServletScopedResponse.MutableHttpServletResponse mutableServletResponse = 
            (servletScopedResponse == null ? null : servletScopedResponse.getMutableHttpServletResponse());
        
        //Get the session that was selected by resolveRequestedSessionId
        Session session = ServletScopedRequest.MutableHttpServletRequest.getSession(mutableServletRequest.getSession());
        
        //Update the cookie
        HttpCookie cookie = access(session, request.getConnectionMetaData().isSecure());

        // Handle changed ID or max-age refresh, but only if this is not a redispatched request
        if (cookie != null)
            Response.replaceCookie(servletScopedResponse, cookie);

        request.getHttpChannel().addStreamWrapper(s ->
        new HttpStream.Wrapper(s)
        {
            @Override
            public void send(MetaData.Response response, boolean last, Callback callback, ByteBuffer... content)
            {
                if (response != null)
                {
                    // Write out session
                    if (session != null)
                        commit(session);
                }
                super.send(response, last, callback, content);
            }

            @Override
            public void succeeded()
            {
                super.succeeded();
                // Leave session
                if (session != null)
                    complete(session);
            }

            @Override
            public void failed(Throwable x)
            {
                super.failed(x);
                //Leave session
                if (session != null)
                    complete(session);

            }
        });

        return super.handle(request);
    }
}