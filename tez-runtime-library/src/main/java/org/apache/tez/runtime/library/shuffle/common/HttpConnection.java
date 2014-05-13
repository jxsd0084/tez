/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.tez.runtime.library.shuffle.common;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.GeneralSecurityException;

import javax.crypto.SecretKey;
import javax.net.ssl.HttpsURLConnection;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.io.DataOutputBuffer;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.security.ssl.SSLFactory;
import org.apache.tez.runtime.library.common.security.SecureShuffleUtils;
import org.apache.tez.runtime.library.common.shuffle.impl.ShuffleHeader;

/**
 * HttpConnection which can be used for Unordered / Ordered shuffle.
 */
public class HttpConnection {

  private static final Log LOG = LogFactory.getLog(HttpConnection.class);

  /** Basic/unit connection timeout (in milliseconds) */
  private final static int UNIT_CONNECT_TIMEOUT = 60 * 1000;

  private URL url;
  private final String logIdentifier;

  private SSLFactory sslFactory;
  private HttpURLConnection connection;
  private DataInputStream input;

  private boolean connectionSucceeed;
  private volatile boolean cleanup;

  private final SecretKey jobTokenSecret;
  private String encHash;
  private String msgToEncode;

  private final HttpConnectionParams httpConnParams;

  /**
   * HttpConnection
   * 
   * @param url
   * @param connParams
   * @param logIdentifier
   * @param jobTokenSecret
   * @throws IOException
   */
  public HttpConnection(URL url, HttpConnectionParams connParams,
      String logIdentifier, SecretKey jobTokenSecret) throws IOException {
    this.logIdentifier = logIdentifier;
    this.jobTokenSecret = jobTokenSecret;
    this.httpConnParams = connParams;
    this.url = url;
    if (LOG.isDebugEnabled()) {
      LOG.debug("MapOutput URL :" + url.toString());
    }
  }

  /**
   * Set ssl factory
   * 
   * @param factory
   */
  public void setSSLFactory(SSLFactory factory) {
    this.sslFactory = factory;
  }

  private void setupConnection() throws IOException {
    connection = (HttpURLConnection) url.openConnection();
    if (sslFactory != null) {
      try {
        ((HttpsURLConnection) connection).setSSLSocketFactory(sslFactory
          .createSSLSocketFactory());
        ((HttpsURLConnection) connection).setHostnameVerifier(sslFactory
          .getHostnameVerifier());
      } catch (GeneralSecurityException ex) {
        throw new IOException(ex);
      }
    }
    // generate hash of the url
    msgToEncode = SecureShuffleUtils.buildMsgFrom(url);
    encHash = SecureShuffleUtils.hashFromString(msgToEncode, jobTokenSecret);

    // put url hash into http header
    connection.addRequestProperty(SecureShuffleUtils.HTTP_HEADER_URL_HASH,
      encHash);
    // set the read timeout
    connection.setReadTimeout(httpConnParams.readTimeout);
    // put shuffle version into http header
    connection.addRequestProperty(ShuffleHeader.HTTP_HEADER_NAME,
      ShuffleHeader.DEFAULT_HTTP_HEADER_NAME);
    connection.addRequestProperty(ShuffleHeader.HTTP_HEADER_VERSION,
      ShuffleHeader.DEFAULT_HTTP_HEADER_VERSION);
  }

  /**
   * Connect to source
   * 
   * @return
   * @throws IOException
   */
  public boolean connect() throws IOException {
    return connect(httpConnParams.connectionTimeout);
  }

  /**
   * Connect to source with specific timeout
   * 
   * @param connectionTimeout
   * @return
   * @throws IOException
   */
  public boolean connect(int connectionTimeout) throws IOException {
    if (connection == null) {
      setupConnection();
    }
    int unit = 0;
    if (connectionTimeout < 0) {
      throw new IOException("Invalid timeout " + "[timeout = "
          + connectionTimeout + " ms]");
    } else if (connectionTimeout > 0) {
      unit = Math.min(UNIT_CONNECT_TIMEOUT, connectionTimeout);
    }
    // set the connect timeout to the unit-connect-timeout
    connection.setConnectTimeout(unit);
    while (true) {
      try {
        connection.connect();
        connectionSucceeed = true;
        break;
      } catch (IOException ioe) {
        // Don't attempt another connect if already cleanedup.
        if (!cleanup) {
          LOG.info("Cleanup is set to true. Not attempting to"
              + " connect again. Last exception was: ["
              + ioe.getClass().getName() + ", " + ioe.getMessage() + "]");
          return false;
        }
        // update the total remaining connect-timeout
        connectionTimeout -= unit;
        // throw an exception if we have waited for timeout amount of time
        // note that the updated value if timeout is used here
        if (connectionTimeout == 0) {
          throw ioe;
        }
        // reset the connect timeout for the last try
        if (connectionTimeout < unit) {
          unit = connectionTimeout;
          // reset the connect time out for the final connect
          connection.setConnectTimeout(unit);
        }
      }
    }
    return true;
  }

  public void validate() throws IOException {
    int rc = connection.getResponseCode();
    if (rc != HttpURLConnection.HTTP_OK) {
      throw new IOException("Got invalid response code " + rc + " from " + url
          + ": " + connection.getResponseMessage());
    }
    // get the shuffle version
    if (!ShuffleHeader.DEFAULT_HTTP_HEADER_NAME.equals(connection
      .getHeaderField(ShuffleHeader.HTTP_HEADER_NAME))
        || !ShuffleHeader.DEFAULT_HTTP_HEADER_VERSION.equals(connection
          .getHeaderField(ShuffleHeader.HTTP_HEADER_VERSION))) {
      throw new IOException("Incompatible shuffle response version");
    }
    // get the replyHash which is HMac of the encHash we sent to the server
    String replyHash =
        connection
          .getHeaderField(SecureShuffleUtils.HTTP_HEADER_REPLY_URL_HASH);
    if (replyHash == null) {
      throw new IOException("security validation of TT Map output failed");
    }
    LOG.debug("url=" + msgToEncode + ";encHash=" + encHash + ";replyHash="
        + replyHash);
    // verify that replyHash is HMac of encHash
    SecureShuffleUtils.verifyReply(replyHash, encHash, jobTokenSecret);
    LOG.info("for url=" + msgToEncode + " sent hash and receievd reply");
  }

  /**
   * Get the inputstream from the connection
   * 
   * @return DataInputStream
   * @throws IOException
   */
  public DataInputStream getInputStream() throws IOException {
    DataInputStream input = null;
    if (connectionSucceeed) {
      input =
          new DataInputStream(new BufferedInputStream(
            connection.getInputStream(), httpConnParams.bufferSize));
    }
    return input;
  }

  /**
   * Cleanup the connection.
   * 
   * @param disconnect
   *          Close the connection if this is true; otherwise respect keepalive
   * @throws IOException
   */
  public void cleanup(boolean disconnect) throws IOException {
    cleanup = true;
    try {
      if (input != null) {
        LOG.info("Closing input on " + logIdentifier);
        input.close();
      }
      if (httpConnParams.keepAlive && connectionSucceeed) {
        // Refer:
        // http://docs.oracle.com/javase/6/docs/technotes/guides/net/http-keepalive.html
        readErrorStream(connection.getErrorStream());
      }
      if (connection != null && (disconnect || !httpConnParams.keepAlive)) {
        LOG.info("Closing connection on " + logIdentifier);
        connection.disconnect();
      }
    } catch (IOException e) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Exception while shutting down fetcher " + logIdentifier, e);
      } else {
        LOG.info("Exception while shutting down fetcher " + logIdentifier
            + ": " + e.getMessage());
      }
    }
  }

  /**
   * Cleanup the error stream if any, for keepAlive connections
   * 
   * @param errorStream
   */
  private void readErrorStream(InputStream errorStream) {
    if (errorStream == null) {
      return;
    }
    try {
      DataOutputBuffer errorBuffer = new DataOutputBuffer();
      IOUtils.copyBytes(errorStream, errorBuffer, 4096);
      IOUtils.closeStream(errorBuffer);
      IOUtils.closeStream(errorStream);
    } catch (IOException ioe) {
      // ignore
    }
  }

  public static class HttpConnectionParams {
    private boolean keepAlive;
    private int keepAliveMaxConnections;
    private int connectionTimeout;
    private int readTimeout;
    private int bufferSize;
    private boolean sslShuffle;
    private SSLFactory sslFactory;

    public boolean getKeepAlive() {
      return keepAlive;
    }

    public int getKeepAliveMaxConnections() {
      return keepAliveMaxConnections;
    }

    public int getConnectionTimeout() {
      return connectionTimeout;
    }

    public int getReadTimeout() {
      return readTimeout;
    }

    public void setReadTimeout(int readTimeout) {
      this.readTimeout = readTimeout;
    }

    public int getBufferSize() {
      return bufferSize;
    }

    public boolean isSSLShuffleEnabled() {
      return sslShuffle;
    }

    public SSLFactory getSSLFactory() {
      return sslFactory;
    }
  }

  public static class HttpConnectionParamsBuilder {
    private HttpConnectionParams params;

    public HttpConnectionParamsBuilder() {
      params = new HttpConnectionParams();
    }

    public HttpConnectionParamsBuilder setKeepAlive(boolean keepAlive,
        int keepAliveMaxConnections) {
      params.keepAlive = keepAlive;
      params.keepAliveMaxConnections = keepAliveMaxConnections;
      return this;
    }

    public HttpConnectionParamsBuilder setTimeout(int connectionTimeout,
        int readTimeout) {
      params.connectionTimeout = connectionTimeout;
      params.readTimeout = readTimeout;
      return this;
    }

    public HttpConnectionParamsBuilder setSSL(boolean sslEnabled,
        SSLFactory sslFactory) {
      params.sslShuffle = true;
      params.sslFactory = sslFactory;
      return this;
    }

    public HttpConnectionParamsBuilder setBufferSize(int bufferSize) {
      params.bufferSize = bufferSize;
      return this;
    }

    public HttpConnectionParams build() {
      return params;
    }
  }
}

