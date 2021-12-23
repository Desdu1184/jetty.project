package org.eclipse.jetty.servlet6.experimental;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.thread.Scheduler;

public class AsyncContextEvent extends AsyncEvent implements Runnable
{
    private final ServletContext _servletContext;
    private final ContextHandler.Context _context;
    private final AsyncContextState _asyncContext;
    private final HttpURI _baseURI;
    private final ServletRequestState _state;
    private ServletContext _dispatchContext;
    private String _dispatchPath;
    private volatile Scheduler.Task _timeoutTask;
    private Throwable _throwable;

    public AsyncContextEvent(ContextHandler.Context context, AsyncContextState asyncContext, ServletRequestState state, Request baseRequest, ServletRequest request, ServletResponse response)
    {
        this(context, asyncContext, state, baseRequest, request, response, null);
    }

    public AsyncContextEvent(ContextHandler.Context context, AsyncContextState asyncContext, ServletRequestState state, Request baseRequest, ServletRequest request, ServletResponse response, HttpURI baseURI)
    {
        super(null, request, response, null);
        _context = context;
        _asyncContext = asyncContext;
        _servletContext = ServletContextHandler.getServletContext(context);
        _state = state;
        _baseURI = baseURI;

        // TODO: Should we store a wrapped request with the attributes?
        // We are setting these attributes during startAsync, when the spec implies that
        // they are only available after a call to AsyncContext.dispatch(...);
        // baseRequest.setAsyncAttributes();
    }

    public HttpURI getBaseURI()
    {
        return _baseURI;
    }

    public ServletContext getSuspendedContext()
    {
        return _servletContext;
    }

    public ServletContext getDispatchContext()
    {
        return _dispatchContext;
    }

    public ServletContext getServletContext()
    {
        return _dispatchContext == null ? _servletContext : _dispatchContext;
    }

    public ContextHandler.Context getContext()
    {
        return _context;
    }

    public void setTimeoutTask(Scheduler.Task task)
    {
        _timeoutTask = task;
    }

    public boolean hasTimeoutTask()
    {
        return _timeoutTask != null;
    }

    public void cancelTimeoutTask()
    {
        Scheduler.Task task = _timeoutTask;
        _timeoutTask = null;
        if (task != null)
            task.cancel();
    }

    @Override
    public AsyncContext getAsyncContext()
    {
        return _asyncContext;
    }

    @Override
    public Throwable getThrowable()
    {
        return _throwable;
    }

    public void setDispatchContext(ServletContext context)
    {
        _dispatchContext = context;
    }

    /**
     * @return The path in the context (encoded with possible query string)
     */
    public String getDispatchPath()
    {
        return _dispatchPath;
    }

    /**
     * @param path encoded URI
     */
    public void setDispatchPath(String path)
    {
        _dispatchPath = path;
    }

    public void completed()
    {
        _timeoutTask = null;
        _asyncContext.reset();
    }

    public ServletRequestState getHttpChannelState()
    {
        return _state;
    }

    @Override
    public void run()
    {
        Scheduler.Task task = _timeoutTask;
        _timeoutTask = null;
        if (task != null)
            _state.timeout();
    }

    public void addThrowable(Throwable e)
    {
        if (_throwable == null)
            _throwable = e;
        else if (e != _throwable)
            _throwable.addSuppressed(e);
    }
}
