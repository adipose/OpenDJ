/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions Copyright [year] [name of copyright owner]".
 *
 * Copyright 2008-2010 Sun Microsystems, Inc.
 * Portions Copyright 2011-2016 ForgeRock AS.
 */
package org.opends.quicksetup.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.net.ssl.TrustManager;

import org.forgerock.i18n.LocalizableMessage;
import org.forgerock.i18n.LocalizableMessageBuilder;
import org.forgerock.i18n.slf4j.LocalizedLogger;
import org.forgerock.opendj.ldap.DN;
import org.forgerock.opendj.ldap.LdapException;
import org.opends.admin.ads.util.ConnectionWrapper;
import org.opends.quicksetup.Application;
import org.opends.quicksetup.ApplicationException;
import org.opends.quicksetup.Configuration;
import org.opends.quicksetup.Installation;
import org.opends.quicksetup.ReturnCode;
import org.opends.quicksetup.installer.InstallerHelper;
import org.opends.server.types.HostPort;
import org.opends.server.util.SetupUtils;

import com.forgerock.opendj.cli.CliConstants;
import com.forgerock.opendj.util.StaticUtils;

import static com.forgerock.opendj.cli.ArgumentConstants.*;
import static com.forgerock.opendj.cli.Utils.*;
import static com.forgerock.opendj.util.OperatingSystem.*;

import static org.opends.admin.ads.util.PreferredConnection.Type.*;
import static org.opends.messages.QuickSetupMessages.*;
import static org.opends.server.util.CollectionUtils.*;

/** Class used to manipulate an OpenDS server. */
public class ServerController {

  private static final LocalizedLogger logger = LocalizedLogger.getLoggerForThisClass();

  private Application application;

  private Installation installation;

  /**
   * Creates a new instance that will operate on <code>application</code>'s
   * installation.
   * @param application to use for notifications
   */
  public ServerController(Application application) {
    this(application, application.getInstallation());
  }

  /**
   * Creates a new instance that will operate on <code>application</code>'s
   * installation.
   * @param installation representing the server instance to control
   */
  public ServerController(Installation installation) {
    this(null, installation);
  }

  /**
   * Creates a new instance that will operate on <code>installation</code>
   * and use <code>application</code> for notifications.
   * @param application to use for notifications
   * @param installation representing the server instance to control
   */
  public ServerController(Application application, Installation installation) {
    if (installation == null) {
      throw new NullPointerException("installation cannot be null");
    }
    this.application = application;
    this.installation = installation;
  }

  /**
   * This methods stops the server.
   *
   * @throws org.opends.quicksetup.ApplicationException if something goes wrong.
   */
  public void stopServer() throws ApplicationException {
    stopServer(false);
  }

  /**
   * This methods stops the server.
   *
   * @param suppressOutput boolean indicating that ouput to standard output
   *                       streams from the server should be suppressed.
   * @throws org.opends.quicksetup.ApplicationException
   *          if something goes wrong.
   */
  public void stopServer(boolean suppressOutput) throws ApplicationException {
    stopServer(suppressOutput,false);
  }
  /**
   * This methods stops the server.
   *
   * @param suppressOutput boolean indicating that ouput to standard output
   *                       streams from the server should be suppressed.
   * @param noPropertiesFile boolean indicating if the stopServer should
   *                       be called without taking into account the
   *                       properties file.
   * @throws org.opends.quicksetup.ApplicationException
   *          if something goes wrong.
   */
  public void stopServer(boolean suppressOutput,boolean noPropertiesFile)
  throws ApplicationException {

    if (suppressOutput && !StandardOutputSuppressor.isSuppressed()) {
      StandardOutputSuppressor.suppress();
    }

    if (suppressOutput && application != null)
    {
      application.setNotifyListeners(false);
    }

    try {
      if (application != null) {
        LocalizableMessageBuilder mb = new LocalizableMessageBuilder();
        mb.append(application.getFormattedProgress(
                        INFO_PROGRESS_STOPPING.get()));
        mb.append(application.getLineBreak());
        application.notifyListeners(mb.toMessage());
      }
      logger.info(LocalizableMessage.raw("stopping server"));

      ArrayList<String> argList = new ArrayList<>();
      argList.add(Utils.getScriptPath(
          Utils.getPath(installation.getServerStopCommandFile())));
      int size = argList.size();
      if (noPropertiesFile)
      {
        size++;
      }
      String[] args = new String[size];
      argList.toArray(args);
      if (noPropertiesFile)
      {
        args[argList.size()] = "--" + OPTION_LONG_NO_PROP_FILE;
      }
      ProcessBuilder pb = new ProcessBuilder(args);
      Map<String, String> env = pb.environment();
      env.put(SetupUtils.OPENDJ_JAVA_HOME, System.getProperty("java.home"));
      env.remove(SetupUtils.OPENDJ_JAVA_ARGS);
      env.remove("CLASSPATH");

      logger.info(LocalizableMessage.raw("Before calling stop-ds.  Is server running? "+
          installation.getStatus().isServerRunning()));

      int stopTries = 3;
      while (stopTries > 0)
      {
        stopTries --;
        logger.info(LocalizableMessage.raw("Launching stop command, stopTries left: "+
            stopTries));

        try
        {
          logger.info(LocalizableMessage.raw("Launching stop command, argList: "+argList));
          Process process = pb.start();

          BufferedReader err =
            new BufferedReader(
                new InputStreamReader(process.getErrorStream()));
          BufferedReader out =
            new BufferedReader(
                new InputStreamReader(process.getInputStream()));

          /* Create these objects to resend the stop process output to the details area. */
          new StopReader(err, true);
          new StopReader(out, false);

          int returnValue = process.waitFor();

          int clientSideError =
            org.opends.server.protocols.ldap.
            LDAPResultCode.CLIENT_SIDE_CONNECT_ERROR;
          if (isWindows()
              && (returnValue == clientSideError || returnValue == 0)) {
            /*
             * Sometimes the server keeps some locks on the files.
             * TODO: remove this code once stop-ds returns properly when
             * server is stopped.
             */
            int nTries = 10;
            boolean stopped = false;
            for (int i = 0; i < nTries && !stopped; i++) {
              logger.trace("waiting for server to stop");
              try {
                Thread.sleep(5000);
              }
              catch (Exception ex)
              {
                // do nothing
              }
              stopped = !installation.getStatus().isServerRunning();
              logger.info(LocalizableMessage.raw(
                  "After calling stop-ds.  Is server running? " + !stopped));
              if (stopped) {
                break;
              }
              if (application != null) {
                LocalizableMessageBuilder mb = new LocalizableMessageBuilder();
                mb.append(application.getFormattedLog(
                    INFO_PROGRESS_SERVER_WAITING_TO_STOP.get()));
                mb.append(application.getLineBreak());
                application.notifyListeners(mb.toMessage());
              }
            }
            if (!stopped) {
              returnValue = -1;
            }
          }

          if (returnValue == clientSideError) {
            if (application != null) {
              LocalizableMessageBuilder mb = new LocalizableMessageBuilder();
              mb.append(application.getLineBreak());
              mb.append(application.getFormattedLog(
                  INFO_PROGRESS_SERVER_ALREADY_STOPPED.get()));
              mb.append(application.getLineBreak());
              application.notifyListeners(mb.toMessage());
            }
            logger.info(LocalizableMessage.raw("server already stopped"));
            break;
          } else if (returnValue != 0) {
            if (stopTries <= 0)
            {
              /* The return code is not the one expected, assume the server could not be stopped. */
              throw new ApplicationException(
                  ReturnCode.STOP_ERROR,
                  INFO_ERROR_STOPPING_SERVER_CODE.get(returnValue),
                  null);
            }
          } else {
            if (application != null) {
              application.notifyListeners(application.getFormattedLog(
                  INFO_PROGRESS_SERVER_STOPPED.get()));
            }
            logger.info(LocalizableMessage.raw("server stopped"));
            break;
          }

        } catch (Exception e) {
          throw new ApplicationException(
              ReturnCode.STOP_ERROR, getThrowableMsg(
                  INFO_ERROR_STOPPING_SERVER.get(), e), e);
        }
      }
    }
    finally {
      if (suppressOutput)
      {
        if (StandardOutputSuppressor.isSuppressed())
        {
          StandardOutputSuppressor.unsuppress();
        }
        if (application != null)
        {
          application.setNotifyListeners(true);
        }
      }
    }
  }

  /**
   * This methods starts the server.
   *
   *@throws org.opends.quicksetup.ApplicationException if something goes wrong.
   */
  public void startServer() throws ApplicationException {
    startServer(true, false);
  }

  /**
   * This methods starts the server.
   * @param suppressOutput boolean indicating that ouput to standard output
   * streams from the server should be suppressed.
   * @throws org.opends.quicksetup.ApplicationException if something goes wrong.
   */
  public void startServer(boolean suppressOutput)
          throws ApplicationException
  {
    startServer(true, suppressOutput);
  }

  /**
   * This methods starts the server.
   * @param verifyCanConnect boolean indicating whether this method will attempt to
   * connect to the server after starting to verify that it is listening.
   * @param suppressOutput indicating that ouput to standard output streams
   * from the server should be suppressed.
   * @param trustManager can be null
   * @throws org.opends.quicksetup.ApplicationException if something goes wrong.
   */
  private void startServer(boolean verifyCanConnect, boolean suppressOutput)
  throws ApplicationException
  {
    if (suppressOutput && !StandardOutputSuppressor.isSuppressed()) {
      StandardOutputSuppressor.suppress();
    }

    if (suppressOutput && application != null)
    {
      application.setNotifyListeners(false);
    }

    try {
      if (application != null) {
        LocalizableMessageBuilder mb = new LocalizableMessageBuilder();
        mb.append(application.getFormattedProgress(
            INFO_PROGRESS_STARTING.get()));
        mb.append(application.getLineBreak());
        application.notifyListeners(mb.toMessage());
      }

      try
      {
        startServerViaAnotherProcess();

        if (verifyCanConnect)
        {
          verifyCanConnect();
        }
      } catch (IOException | InterruptedException ioe)
      {
        throw new ApplicationException(
            ReturnCode.START_ERROR,
            getThrowableMsg(INFO_ERROR_STARTING_SERVER.get(), ioe), ioe);
      }
    } finally {
      if (suppressOutput)
      {
        if (StandardOutputSuppressor.isSuppressed())
        {
          StandardOutputSuppressor.unsuppress();
        }
        if (application != null)
        {
          application.setNotifyListeners(true);
        }
      }
    }
  }

  private void startServerViaAnotherProcess() throws IOException, InterruptedException, ApplicationException
  {
    logger.info(LocalizableMessage.raw("starting server"));

    List<String> argList = newArrayList(
        Utils.getScriptPath(Utils.getPath(installation.getServerStartCommandFile())),
        "--timeout", "0");

    ProcessBuilder pb = new ProcessBuilder(argList.toArray(new String[argList.size()]));
    pb.directory(installation.getBinariesDirectory());
    Map<String, String> env = pb.environment();
    env.put(SetupUtils.OPENDJ_JAVA_HOME, System.getProperty("java.home"));
    env.remove(SetupUtils.OPENDJ_JAVA_ARGS);

    // Upgrader's classpath contains jars located in the temporary
    // directory that we don't want locked by the directory server
    // when it starts. Since we're just calling the start-ds script
    // it will figure out the correct classpath for the server.
    env.remove("CLASSPATH");

    String startedId = getStartedId();
    Process process = pb.start();

    BufferedReader err = new BufferedReader(new InputStreamReader(process.getErrorStream()));
    BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream()));

    StartReader errReader = new StartReader(err, startedId, true);
    StartReader outputReader = new StartReader(out, startedId, false);

    int returnValue = process.waitFor();

    logger.info(LocalizableMessage.raw("start-ds return value: " + returnValue));

    if (returnValue != 0)
    {
      throw new ApplicationException(ReturnCode.START_ERROR, INFO_ERROR_STARTING_SERVER_CODE.get(returnValue), null);
    }
    if (outputReader.isFinished())
    {
      logger.info(LocalizableMessage.raw("Output reader finished."));
    }
    if (errReader.isFinished())
    {
      logger.info(LocalizableMessage.raw("Error reader finished."));
    }
    if (!outputReader.startedIdFound() && !errReader.startedIdFound())
    {
      logger.warn(LocalizableMessage.raw("Started ID could not be found"));
    }

    // Check if something wrong occurred reading the starting of the server
    ApplicationException ex = errReader.getException();
    if (ex == null)
    {
      ex = outputReader.getException();
    }
    if (ex != null)
    {
      // This is meaningless right now since we throw
      // the exception below, but in case we change out
      // minds later or add the ability to return exceptions
      // in the output only instead of throwing...
      throw ex;
    }
  }

  private void verifyCanConnect() throws IOException, ApplicationException
  {
    /*
     * There are no exceptions from the readers and they are marked as
     * finished. So it seems that everything went fine.
     *
     * However we can have issues with the firewalls or do not have rights
     * to connect or since the startup process is asynchronous we will
     * have to wait for the databases and the listeners to initialize.
     * Just check if we can connect to the server.
     * Try 30 times with an interval of 3 seconds between try.
     */
    boolean connected = false;
    Configuration config = installation.getCurrentConfiguration();
    int port = config.getAdminConnectorPort();

    // See if the application has prompted for credentials.  If
    // not we'll just try to connect anonymously.
    DN userDn = null;
    String userPw = null;
    if (application != null) {
      userDn = application.getUserData().getDirectoryManagerDn();
      userPw = application.getUserData().getDirectoryManagerPwd();
    }
    if (userDn == null || userPw == null) {
      userDn = null;
      userPw = null;
    }
    
    TrustManager trustManager = null;
    if (StaticUtils.isFips()) {
      trustManager = application.getTrustManager().getX509TrustManager();
    }

    for (int i=0; i<50 && !connected; i++)
    {
      int timeout = CliConstants.DEFAULT_LDAP_CONNECT_TIMEOUT;
      if (application != null && application.getUserData() != null)
      {
        timeout = application.getUserData().getConnectTimeout();
      }
      HostPort hp = new HostPort(getHostName(i), port);
      try (ConnectionWrapper conn = new ConnectionWrapper(hp, LDAPS, userDn, userPw, timeout, trustManager))
      {
        return;
      }
      catch (LdapException ne)
      {
        logger.warn(LocalizableMessage.raw("Could not connect to server: "+ne, ne));
      }

      try
      {
        // not connected yet
        Thread.sleep(3000);
      }
      catch (Throwable t)
      {
        // do nothing
      }
    }

    // Could not connect
    final LocalizableMessage msg = isWindows()
        ? INFO_ERROR_STARTING_SERVER_IN_WINDOWS.get(port)
        : INFO_ERROR_STARTING_SERVER_IN_UNIX.get(port);
    throw new ApplicationException(ReturnCode.START_ERROR, msg, null);
  }

  private String getHostName(int i)
  {
    String hostName = null;
    if (application != null)
    {
      hostName = application.getUserData().getHostName();
    }
    if (hostName == null)
    {
      hostName = "localhost";
    }

    int dig = i % 10;

    if ((dig == 3 || dig == 4) && !"localhost".equals(hostName))
    {
      // Try with local host. This might be necessary in certain
      // network configurations.
      hostName = "localhost";
    }

    if (dig == 5 || dig == 6)
    {
      // Try with 0.0.0.0. This might be necessary in certain
      // network configurations.
      hostName = "0.0.0.0";
    }

    hostName = getHostNameForLdapUrl(hostName);
    return hostName;
  }

  /**
   * This class is used to read the standard error and standard output of the
   * Stop process.
   * <p/>
   * When a new log message is found notifies the
   * UninstallProgressUpdateListeners of it. If an error occurs it also
   * notifies the listeners.
   */
  private class StopReader {
    private boolean isFirstLine;

    /**
     * The protected constructor.
     *
     * @param reader  the BufferedReader of the stop process.
     * @param isError a boolean indicating whether the BufferedReader
     *        corresponds to the standard error or to the standard output.
     */
    public StopReader(final BufferedReader reader,
                                      final boolean isError) {
      final LocalizableMessage errorTag =
              isError ?
                      INFO_ERROR_READING_ERROROUTPUT.get() :
                      INFO_ERROR_READING_OUTPUT.get();

      isFirstLine = true;
      Thread t = new Thread(new Runnable() {
        @Override
        public void run() {
          try {
            String line = reader.readLine();
            while (line != null) {
              if (application != null) {
                LocalizableMessageBuilder buf = new LocalizableMessageBuilder();
                if (!isFirstLine) {
                  buf.append(application.getProgressMessageFormatter().
                          getLineBreak());
                }
                if (isError) {
                  buf.append(application.getFormattedLogError(
                          LocalizableMessage.raw(line)));
                } else {
                  buf.append(application.getFormattedLog(
                          LocalizableMessage.raw(line)));
                }
                application.notifyListeners(buf.toMessage());
                isFirstLine = false;
              }
              logger.info(LocalizableMessage.raw("server: " + line));
              line = reader.readLine();
            }
          } catch (Throwable t) {
            if (application != null) {
              LocalizableMessage errorMsg = getThrowableMsg(errorTag, t);
              application.notifyListeners(errorMsg);
            }
            logger.info(LocalizableMessage.raw("error reading server messages",t));
          }
        }
      });
      t.start();
    }
  }

  /**
   * Returns the LocalizableMessage ID indicating that the server has started.
   * @return the LocalizableMessage ID indicating that the server has started.
   */
  private String getStartedId()
  {
    InstallerHelper helper = new InstallerHelper();
    return helper.getStartedId();
  }

  /**
   * This class is used to read the standard error and standard output of the
   * Start process.
   *
   * When a new log message is found notifies the ProgressUpdateListeners
   * of it. If an error occurs it also notifies the listeners.
   */
  private class StartReader
  {
    private ApplicationException ex;

    private boolean isFinished;

    private boolean startedIdFound;

    private boolean isFirstLine;

    /**
     * The protected constructor.
     * @param reader the BufferedReader of the start process.
     * @param startedId the message ID that this class can use to know whether
     * the start is over or not.
     * @param isError a boolean indicating whether the BufferedReader
     * corresponds to the standard error or to the standard output.
     */
    public StartReader(final BufferedReader reader, final String startedId,
        final boolean isError)
    {
      final LocalizableMessage errorTag =
              isError ?
                      INFO_ERROR_READING_ERROROUTPUT.get() :
                      INFO_ERROR_READING_OUTPUT.get();

      isFirstLine = true;

      Thread t = new Thread(new Runnable()
      {
        @Override
        public void run()
        {
          try
          {
            String line = reader.readLine();
            while (line != null)
            {
              if (application != null) {
                LocalizableMessageBuilder buf = new LocalizableMessageBuilder();
                if (!isFirstLine)
                {
                  buf.append(application.getProgressMessageFormatter().
                          getLineBreak());
                }
                if (isError)
                {
                  buf.append(application.getFormattedLogError(
                          LocalizableMessage.raw(line)));
                } else
                {
                  buf.append(application.getFormattedLog(
                          LocalizableMessage.raw(line)));
                }
                application.notifyListeners(buf.toMessage());
                isFirstLine = false;
              }
              logger.info(LocalizableMessage.raw("server: " + line));
              if (line.toLowerCase().contains("=" + startedId))
              {
                isFinished = true;
                startedIdFound = true;
              }
              line = reader.readLine();
            }
          } catch (Throwable t)
          {
            logger.warn(LocalizableMessage.raw("Error reading output: "+t, t));
            ex = new ApplicationException(
                ReturnCode.START_ERROR,
                getThrowableMsg(errorTag, t), t);

          }
          isFinished = true;
        }
      });
      t.start();
    }

    /**
     * Returns the ApplicationException that occurred reading the Start error
     * and output or <CODE>null</CODE> if no exception occurred.
     * @return the exception that occurred reading or <CODE>null</CODE> if
     * no exception occurred.
     */
    public ApplicationException getException()
    {
      return ex;
    }

    /**
     * Returns <CODE>true</CODE> if the server starting process finished
     * (successfully or not) and <CODE>false</CODE> otherwise.
     * @return <CODE>true</CODE> if the server starting process finished
     * (successfully or not) and <CODE>false</CODE> otherwise.
     */
    public boolean isFinished()
    {
      return isFinished;
    }

    /**
     * Returns <CODE>true</CODE> if the server start Id was found and
     * <CODE>false</CODE> otherwise.
     * @return <CODE>true</CODE> if the server start Id was found and
     * <CODE>false</CODE> otherwise.
     */
    public boolean startedIdFound()
    {
      return startedIdFound;
    }
  }

}
