/*
 * (c) 2009-2012 Julien Rialland, and the jFastCGI project developers.
 * Released under BSD License, see LICENSE_JRIALLAND.txt
 */
/*
 Copyright (c) 2013-2016 - the jFastCGI project developers.

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */
package org.jfastcgi.client;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecuteResultHandler;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteStreamHandler;
import org.apache.commons.exec.ExecuteException;
import org.apache.commons.exec.ExecuteWatchdog;
import org.apache.commons.exec.Executor;

import org.jfastcgi.api.ConnectionFactory;
import org.jfastcgi.api.RequestAdapter;
import org.jfastcgi.api.ResponseAdapter;
import org.jfastcgi.utils.logging.StreamLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * handling of the fastCGI protocol.
 */
public class FastCGIHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(FastCGIHandler.class);
    private static final int HTTP_ERROR_BAD_GATEWAY = 502;
    private static final int FCGI_BEGIN_REQUEST = 1;
    private static final int FCGI_END_REQUEST = 3;
    private static final int FCGI_PARAMS = 4;
    private static final int FCGI_STDIN = 5;
    private static final int FCGI_STDOUT = 6;
    private static final int FCGI_STDERR = 7;
    private static final int FCGI_RESPONDER = 1;
    private static final int FCGI_VERSION = 1;
    private static final int FCGI_KEEP_CONN = 1;
    private static final int FCGI_REQUEST_COMPLETE = 0;
    private static final long READ_TIMEOUT = 120000;
    private ConnectionFactory connectionFactory;
    private Executor processExecutor;
    private Thread processLogThread;
    private boolean keepAlive = false;
    private short requestId = 1;
    private Pattern phpScriptFromPathPattern = Pattern.compile("\\.php(/|$)");
    /**
     * by default allow some widely used headers and headers used by Nextcloud
     */
    private HeaderFilter headerFilter = new HeaderFilter() {

        private Pattern p = Pattern.compile(
                "ACCEPT[-0-9A-Z]{0,100}|AUTHORIZATION|CACHE-CONTROL|COOKIE|DEPTH"
                + "|HOST|IF-[-0-9A-Z]{2,100}|OCS-APIREQUEST|REFERER|REQUESTTOKEN"
                + "|USER-AGENT|X-FORWARDED-FOR|X-UPDATER-AUTH");

        public boolean isAllowed(final String header) {
            return p.matcher(header.toUpperCase()).matches();
        }
    };

    private static Logger getLog() {
        return LOGGER;
    }

    private synchronized short newRequestId() {
        return requestId++;
    }

    /**
     * Some http headers have sometimes to be filtered for security reasons, so
     * this methods allows to tell which http headers we do want to pass to
     * the fastcgi app. Default is: <code>
     * java.util.Pattern.compile(
     *    "ACCEPT[-0-9A-Z]{0,100}|AUTHORIZATION|CACHE-CONTROL|COOKIE|DEPTH|HOST|IF-[-0-9A-Z]{2,100}|OCS-APIREQUEST|REFERER|REQUESTTOKEN|USER-AGENT|X-FORWARDED-FOR|X-UPDATER-AUTH");
     * </code>
     * <p/>
     *
     * @param allowedHeaders
     *            a regular expression for allowed http headers that will be
     *            transmitted to the fastcgi responder app.
     */
    public void setAllowedHeaders(final Pattern allowedHeaders) {

        headerFilter = new HeaderFilter() {
            private Pattern p = allowedHeaders;

            public boolean isAllowed(final String header) {
                return p.matcher(header.toUpperCase()).matches();
            }
        };
    }
    
    public void setPhpScriptFromPathPattern(final Pattern phpScriptFromPathPattern) {
        this.phpScriptFromPathPattern = phpScriptFromPathPattern;
    }

    /**
     * @param header
     *            the name of a http header (case insensitive)
     * @return true if the header should be allowed.
     */
    protected boolean isHeaderAllowed(final String header) {
        return headerFilter.isAllowed(header);
    }

    public void startProcess(final String cmd) throws IOException {
        final DefaultExecutor pe = new DefaultExecutor();
        processExecutor = pe;
        pe.setWatchdog(new ExecuteWatchdog(60000));

        processExecutor.setStreamHandler(new ExecuteStreamHandler() {

            private final Set<StreamLogger> loggers = new HashSet<StreamLogger>();

            public void stop() throws IOException {

            }

            public void start() throws IOException {

            }

            public void setProcessOutputStream(final InputStream is) throws IOException {
                loggers.add(new StreamLogger(is,
                        LoggerFactory.getLogger(FastCGIHandler.class.getName() + ".externalprocess.stdout")));
            }

            public void setProcessInputStream(final OutputStream os) throws IOException {

            }

            public void setProcessErrorStream(final InputStream is) throws IOException {
                loggers.add(new StreamLogger(is,
                        LoggerFactory.getLogger(FastCGIHandler.class.getName() + ".externalprocess.stderr")));
            }
        });

        getLog().info("Starting external process : " + cmd);
        pe.execute(CommandLine.parse(cmd), new DefaultExecuteResultHandler() {
            @Override
            public void onProcessFailed(final ExecuteException e) {
                super.onProcessFailed(e);
                getLog().error("while running process", e);
            }

            @Override
            public void onProcessComplete(final int exitValue) {
                getLog().info(String.format("external process exited with code %s : %s", exitValue, cmd));
            }
        });

    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        destroy();
    }

    public void service(final RequestAdapter request, final ResponseAdapter response) throws IOException {

        final OutputStream out = response.getOutputStream();

        final ISocket fcgiSocket = connectionFactory.getConnection();
        fcgiSocket.setSoTimeout((int) READ_TIMEOUT);

        try {
            synchronized (fcgiSocket) {
                getLog().debug("connected to " + fcgiSocket.getPseudoUrl());
                handleRequest(request, response, fcgiSocket, out, keepAlive);
            }
        }
        finally {
            if (fcgiSocket != null) {
                getLog().debug("releasing connection to " + fcgiSocket.getPseudoUrl());
                connectionFactory.releaseConnection(fcgiSocket);
                fcgiSocket.close();
            }
        }
    }

    private boolean handleRequest(final RequestAdapter req, final ResponseAdapter res, final ISocket fcgiSocket,
            final OutputStream out, final boolean keepalive) throws IOException {
        final OutputStream ws = fcgiSocket.getOutputStream();

        writeHeader(ws, FCGI_BEGIN_REQUEST, 8);

        final int role = FCGI_RESPONDER;

        ws.write(role >> 8);
        ws.write(role);
        ws.write(keepalive ? FCGI_KEEP_CONN : 0); // flags
        for (int i = 0; i < 5; i++) {
            ws.write(0);
        }

        setEnvironment(ws, req);

        final InputStream in = req.getInputStream();
        final byte[] buf = new byte[4096];
        final int len = buf.length;
        int sublen;

        writeHeader(ws, FCGI_PARAMS, 0);

        boolean hasStdin = false;
        while ((sublen = in.read(buf, 0, len)) > 0) {
            hasStdin = true;
            writeHeader(ws, FCGI_STDIN, sublen);
            ws.write(buf, 0, sublen);
        }

        if (hasStdin) {
            writeHeader(ws, FCGI_STDIN, 0);
        }

        final FastCGIInputStream is = new FastCGIInputStream(fcgiSocket);

        int ch = parseHeaders(res, is);

        if (ch >= 0) {
            out.write(ch);
        }

        while ((ch = is.read()) >= 0) {
            out.write(ch);
        }

        return !is.isDead() && keepalive;
    }

    private void setEnvironment(final OutputStream ws, final RequestAdapter req) throws IOException {
        if (req.getQueryString() != null) {
            addHeader(ws, "REQUEST_URI", req.getRequestURI()+"?"+req.getQueryString());
        }
        else {
            addHeader(ws, "REQUEST_URI", req.getRequestURI());
        }
        addHeader(ws, "REQUEST_METHOD", req.getMethod());
        addHeader(ws, "SERVER_SOFTWARE", FastCGIHandler.class.getName());
        addHeader(ws, "SERVER_NAME", req.getServerName());
        addHeader(ws, "SERVER_PORT", String.valueOf(req.getServerPort()));
        addHeader(ws, "REMOTE_ADDR", req.getRemoteAddr());
        addHeader(ws, "REMOTE_HOST", req.getRemoteHost());

        String remoteUser = req.getRemoteUser();
        addHeader(ws, "REMOTE_USER", remoteUser == null ? "" : remoteUser);

        if (req.getAuthType() != null) {
            addHeader(ws, "AUTH_TYPE", req.getAuthType());
        }

        addHeader(ws, "GATEWAY_INTERFACE", "CGI/1.1");
        addHeader(ws, "SERVER_PROTOCOL", req.getProtocol());

        String queryString = req.getQueryString();
        addHeader(ws, "QUERY_STRING", queryString == null ? "" : queryString);

        String servletPathPathInfo = req.getServletPath() + req.getPathInfo();
        String documentRoot = req.getRealPath("/");
        documentRoot = documentRoot.substring(0, documentRoot.length() - 1);
        
        Matcher findScriptMatcher
                    = phpScriptFromPathPattern.matcher(servletPathPathInfo);
        if (getLog().isDebugEnabled()) {
            getLog().debug("req.getServletPath() + req.getPathInfo(): '" +
                    servletPathPathInfo + "'.");
        }
        if (!findScriptMatcher.find()) {
            // should we log a warning here? Or throw an exception?
            getLog().debug("Regex from config parameter "
                    + FastCGIHandlerFactory.PARAM_PHP_SCRIPT_FROM_PATH
                    + " not found in current URL (" + servletPathPathInfo + ")");
            return;
        }
        if (getLog().isDebugEnabled()) {
            getLog().debug("Pattern matches '" + findScriptMatcher.group()
                    + "at position " + findScriptMatcher.start() + ".");
        }
        int afterScriptIndex = findScriptMatcher.end();
        if (afterScriptIndex > 0
                && afterScriptIndex < servletPathPathInfo.length()) {
            afterScriptIndex--; // Pattern matches '/' after script name
        }
        String pathInfo = servletPathPathInfo.substring(afterScriptIndex);
        String scriptNameWithoutContext = 
                servletPathPathInfo.substring(0, afterScriptIndex);
        
        addHeader(ws, "PATH_INFO", pathInfo);
        addHeader(ws, "SCRIPT_NAME", req.getContextPath() + scriptNameWithoutContext);
        
        // I am not sure about PATH_TRANSLATED. Following
        // https://www.ietf.org/rfc/rfc3875 it must be URL-encoded
        if (!pathInfo.isEmpty()) {
            addHeader(ws, "PATH_TRANSLATED", documentRoot + "/"
                        + URLEncoder.encode(pathInfo.substring(1), "UTF-8"));
        }
        
        addHeader(ws, "SCRIPT_FILENAME", documentRoot + scriptNameWithoutContext);
        
        final long contentLength = req.getContentLength/* "Long" for Servlet 3.1 */();
        if (contentLength > 0) {
            addHeader(ws, "CONTENT_LENGTH", String.valueOf(contentLength));
        }

        addHeader(ws, "DOCUMENT_ROOT", documentRoot);
        

        final Enumeration<String> e = req.getHeaderNames();
        while (e.hasMoreElements()) {
            final String key = e.nextElement().toUpperCase();

            if (isHeaderAllowed(key)) {
                addHeader(ws, "HTTP_" + key.replace('-',  '_'), req.getHeader(key));
            }
        }
    }

    private int parseHeaders(final ResponseAdapter res, final InputStream is) throws IOException {
        String key = "";
        String value = "";

        int ch = is.read();

        if (ch < 0) {
            getLog().error("Can't contact FastCGI");
            res.sendError(HTTP_ERROR_BAD_GATEWAY);
            return -1;
        }

        while (ch >= 0) {

            for (key = ""; ch >= 0 && ch != ' ' && ch != '\r' && ch != '\n' && ch != ':'; ch = is.read()) {
                key += new Character((char) ch).toString();
            }

            while (ch >= 0 && ch == ' ' || ch == ':') {
                ch = is.read();
            }

            for (value = ""; ch >= 0 && ch != '\r' && ch != '\n'; ch = is.read()) {
                value += new Character((char) ch).toString();
            }

            if (ch == '\r') {
                ch = is.read();
                if (ch == '\n') {
                    ch = is.read();
                }
            }

            if (key.length() == 0) {
                return ch;
            }

            if (getLog().isDebugEnabled()) {
                getLog().debug("fastcgi:" + key + ": " + value);
            }

            if (key.equalsIgnoreCase("status")) {
                int status = 0;
                final int len = value.length();

                for (int i = 0; i < len; i++) {
                    final char digit = value.charAt(i);

                    if ('0' <= digit && digit <= '9') {
                        status = 10 * status + digit - '0';
                    }

                    else {
                        break;

                    }
                }

                res.setStatus(status);
            }
            else if (key.equalsIgnoreCase("location")) {
                res.sendRedirect(value.toString());
            }
            else {
                res.addHeader(key.toString(), value.toString());
            }
        }

        return ch;
    }

    private void addHeader(final OutputStream ws, final String key, final String value) throws IOException {

        if (value != null) {

            final int keyLen = key.length();
            final int valLen = value.length();

            int len = keyLen + valLen;

            if (keyLen < 0x80) {
                len += 1;
            }
            else {
                len += 4;
            }

            if (valLen < 0x80) {
                len += 1;
            }
            else {
                len += 4;
            }

            writeHeader(ws, FCGI_PARAMS, len);

            if (keyLen < 0x80) {
                ws.write(keyLen);
            }
            else {
                ws.write(0x80 | keyLen >> 24);
                ws.write(keyLen >> 16);
                ws.write(keyLen >> 8);
                ws.write(keyLen);
            }

            if (valLen < 0x80) {
                ws.write(valLen);
            }
            else {
                ws.write(0x80 | valLen >> 24);
                ws.write(valLen >> 16);
                ws.write(valLen >> 8);
                ws.write(valLen);
            }

            ws.write(key.getBytes());
            ws.write(value.getBytes());
        }
        getLog().debug(String.format("HEADER: key=%s, value=%s", key, value));
    }

    private short writeHeader(final OutputStream ws, final int type, final int length) throws IOException {
        final short id = newRequestId();
        final int pad = 0;

        ws.write(FCGI_VERSION);
        ws.write(type);
        ws.write(id >> 8);
        ws.write(id);
        ws.write(length >> 8);
        ws.write(length);
        ws.write(pad);
        ws.write(0);

        getLog().debug("fcgi request id : " + id);
        return id;
    }

    public void destroy() {
        if (processExecutor != null) {
            processExecutor.getWatchdog().destroyProcess();
            processExecutor = null;
        }
    }

    public Thread getProcessLogThread() {
        return processLogThread;
    }

    public boolean isKeepAlive() {
        return keepAlive;
    }

    public void setKeepAlive(final boolean keepAlive) {
        this.keepAlive = keepAlive;
    }

    public ConnectionFactory getConnectionFactory() {
        return connectionFactory;
    }

    public void setConnectionFactory(final ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    private static interface HeaderFilter {
        public boolean isAllowed(String header);
    }

    static class FastCGIInputStream extends InputStream {
        private ISocket _fcgiSocket;

        private InputStream _is;
        private int _chunkLength;
        private int _padLength;
        private boolean _isDead;

        public FastCGIInputStream() {
        }

        public FastCGIInputStream(final ISocket fcgiSocket) throws IOException {
            init(fcgiSocket);
        }

        public void init(final ISocket fcgiSocket) throws IOException {
            _fcgiSocket = fcgiSocket;

            _is = fcgiSocket.getInputStream();
            _chunkLength = 0;
            _isDead = false;
        }

        public boolean isDead() {
            return _isDead;
        }

        @Override
        public int read() throws IOException {
            do {
                if (_chunkLength > 0) {
                    _chunkLength--;
                    return _is.read();
                }
            } while (readNext());

            return -1;
        }

        private boolean readNext() throws IOException {
            if (_is == null) {
                return false;
            }

            if (_padLength > 0) {
                _is.skip(_padLength);
                _padLength = 0;
            }

            @SuppressWarnings("unused")
            int version;

            while ((version = _is.read()) >= 0) {

                final int type = _is.read();

                @SuppressWarnings("unused")
                final int id = (_is.read() << 8) + _is.read();
                final int length = (_is.read() << 8) + _is.read();
                final int padding = _is.read();
                _is.read();

                switch (type) {
                case FCGI_END_REQUEST:
                    final int appStatus = (_is.read() << 24) + (_is.read() << 16) + (_is.read() << 8) + _is.read();
                    final int pStatus = _is.read();

                    if (getLog().isDebugEnabled()) {
                        getLog().debug(_fcgiSocket + ": FCGI_END_REQUEST(appStatus:" + appStatus + ", pStatus:"
                                + pStatus + ")");
                    }

                    if (appStatus != 0) {
                        _isDead = true;
                    }

                    if (pStatus != FCGI_REQUEST_COMPLETE) {
                        _isDead = true;
                    }

                    _is.skip(3);
                    _is = null;
                    return false;


                case FCGI_STDOUT:
                    if (getLog().isDebugEnabled()) {
                        getLog().debug(_fcgiSocket + ": FCGI_STDOUT(length:" + length + ", padding:" + padding + ")");
                    }

                    if (length == 0) {
                        if (padding > 0) {
                            _is.skip(padding);
                        }

                        break;
                    }
                    else {
                        _chunkLength = length;
                        _padLength = padding;
                        return true;
                    }

                case FCGI_STDERR:
                    if (getLog().isDebugEnabled()) {
                        getLog().debug(_fcgiSocket + ": FCGI_STDERR(length:" + length + ", padding:" + padding + ")");
                    }

                    final byte[] buf = new byte[length];
                    _is.read(buf, 0, length);
                    getLog().warn(new String(buf, 0, length));

                    if (padding > 0) {
                        _is.skip(padding);
                    }
                    break;

                default:
                    getLog().warn(_fcgiSocket + ": Unknown Protocol(" + type + ")");

                    _isDead = true;
                    _is.skip(length + padding);
                    break;
                }
            }

            _isDead = true;

            return false;
        }
    }

}
