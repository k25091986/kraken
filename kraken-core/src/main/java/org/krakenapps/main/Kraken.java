/*
 * Copyright 2009 NCHOVY
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.krakenapps.main;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.instrument.Instrumentation;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import org.apache.felix.framework.Felix;
import org.apache.felix.framework.util.FelixConstants;
import org.apache.felix.framework.util.StringMap;
import org.apache.felix.prefs.impl.PreferencesManager;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.DailyRollingFileAppender;
import org.apache.log4j.Level;
import org.apache.log4j.PatternLayout;
import org.apache.mina.filter.codec.ProtocolCodecFilter;
import org.apache.mina.transport.socket.nio.NioSocketAcceptor;
import org.apache.sshd.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.krakenapps.account.AccountScriptFactory;
import org.krakenapps.api.InstrumentationService;
import org.krakenapps.api.LoggerControlService;
import org.krakenapps.api.ScriptFactory;
import org.krakenapps.bundle.BundleManagerService;
import org.krakenapps.bundle.BundleScript;
import org.krakenapps.bundle.BundleScriptFactory;
import org.krakenapps.console.TelnetCodecFactory;
import org.krakenapps.console.TelnetHandler;
import org.krakenapps.instrumentation.InstrumentationServiceImpl;
import org.krakenapps.keystore.KeyStoreScriptFactory;
import org.krakenapps.logger.KrakenLogService;
import org.krakenapps.logger.LogCleaner;
import org.krakenapps.logger.LoggerScriptFactory;
import org.krakenapps.pkg.PackageScriptFactory;
import org.krakenapps.script.CoreScriptFactory;
import org.krakenapps.script.HistoryScriptFactory;
import org.krakenapps.script.OsgiScriptFactory;
import org.krakenapps.script.OutputOnlyScriptContext;
import org.krakenapps.script.PerfScriptFactory;
import org.krakenapps.script.RegistryScriptFactory;
import org.krakenapps.script.SunPerfScriptFactory;
import org.krakenapps.script.batch.BatchScriptFactory;
import org.krakenapps.ssh.SshCommandFactory;
import org.krakenapps.ssh.SshPasswordAuthenticator;
import org.krakenapps.thread.ThreadScriptFactory;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.launch.Framework;
import org.osgi.service.log.LogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.impl.KrakenLoggerFactory;
import org.slf4j.impl.StaticLoggerBinder;

import sun.misc.Signal;
import sun.misc.SignalHandler;

/**
 * Create the OSGi world using apache felix framework. Kraken class acts as a
 * system bundle.
 * 
 * @author xeraph
 * 
 */
@SuppressWarnings("restriction")
public class Kraken implements BundleActivator, SignalHandler {
	public static String BANNER = "Kraken";

	private static BundleContext context = null;
	public static Instrumentation instrumentation = null;
	private Logger logger = null;

	private Thread logCleaner = null;

	private PreferencesManager prefsManager;

	private static boolean serviceMode = false;

	// temporal access. remind cyclic dependency.
	public static BundleContext getContext() {
		return context;
	}

	public static void premain(String args, Instrumentation inst) {
		instrumentation = inst;
	}

	/**
	 * Entry point.
	 * 
	 * @param args
	 *            use system property instead.
	 * @throws Exception
	 */
	public static void main(String[] args) throws Exception {
		startKraken(new StartOptions(args));
	}

	private static Kraken kraken;

	private static void startKraken(StartOptions startOptions) throws Exception {
		kraken = new Kraken();
		try {
			Signal signal = new Signal("TERM");
			Signal.handle(signal, kraken);
		} catch (Exception e) {
			System.out.println("Signal handling is only supported on Sun JVM");
		}

		kraken.boot(startOptions);
	}

	public static boolean isServiceMode() {
		return serviceMode;
	}

	public static void stopKraken() throws Exception {
		context.getBundle(0).stop();
	}

	public static void windowsService(String[] args) throws Exception {
		String cmd = "start";
		if (args.length > 0) {
			cmd = args[0];
		}

		if ("start".equals(cmd)) {
			serviceMode = true;
			startKraken(new StartOptions());
		} else {
			stopKraken();
		}
	}

	@Override
	public void handle(Signal signal) {
		try {
			context.getBundle(0).stop();
		} catch (BundleException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Boot felix framework up.
	 * 
	 * @param startOptions
	 * 
	 * @throws Exception
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public void boot(StartOptions startOptions) throws Exception {
		if (System.getProperty("kraken.dir") == null) {
			File jarPath = new File(Kraken.class.getProtectionDomain().getCodeSource().getLocation().getPath());
			File dir = jarPath.getParentFile();
			System.setProperty("kraken.dir", dir.getAbsolutePath());
		}

		String krakenDir = System.getProperty("kraken.dir");
		if (System.getProperty("kraken.data.dir") == null)
			System.setProperty("kraken.data.dir", new File(krakenDir, "data").getAbsolutePath());
		if (System.getProperty("kraken.log.dir") == null)
			System.setProperty("kraken.log.dir", new File(krakenDir, "log").getAbsolutePath());
		if (System.getProperty("kraken.cache.dir") == null)
			System.setProperty("kraken.cache.dir", new File(krakenDir, "cache").getAbsolutePath());
		if (System.getProperty("kraken.download.dir") == null)
			System.setProperty("kraken.download.dir", new File(krakenDir, "download").getAbsolutePath());

		setLogger();

		List activators = new ArrayList();
		activators.add(this);

		Map configMap = new StringMap(false);
		Logger logger = LoggerFactory.getLogger(Felix.class.getName());
		configMap.put(FelixConstants.LOG_LOGGER_PROP, logger);
		configMap.put(FelixConstants.LOG_LEVEL_PROP, "3"); // INFO
		configMap.put(FelixConstants.SYSTEMBUNDLE_ACTIVATORS_PROP, activators);
		configMap.put(Constants.FRAMEWORK_SYSTEMPACKAGES, getSystemPackages());
		configMap.put(Constants.FRAMEWORK_STORAGE, new File(System.getProperty("kraken.cache.dir")).getAbsolutePath());

		configMap.put(Constants.FRAMEWORK_BOOTDELEGATION,
				"org.eclipse.tptp.martini,com.jprofiler.*,com.jprofiler.agent.*");

		felix = new Felix(configMap);
		if (startOptions.isDeveloperMode()) {
			felix.init();
			BundleContext bundleContext = felix.getBundleContext();
			BundleManagerService manager = new BundleManagerService(bundleContext);
			BundleScript script = new BundleScript(manager);
			script.setScriptContext(new OutputOnlyScriptContext(logger));
			script.updateAll(new String[] { "force" });
			script.refresh(new String[0]);
			bundleContext.removeBundleListener(manager);
		}
		felix.start();
	}

	private Felix felix = null;

	public Framework getFramework() {
		return felix;
	}

	/**
	 * Load log4j.properties file from working directory by default. If you need
	 * to change log4j configuration file's location, set log4j.configuration
	 * system property.
	 * 
	 * @throws IOException
	 */
	private void setLogger() throws IOException {
		if (new File("log4j.properties").exists()) {
			System.setProperty("log4j.configuration", "file:log4j.properties");
		} else {
			setDefaultLogging();
		}

		logger = LoggerFactory.getLogger(Kraken.class.getName());
	}

	/**
	 * Fetch all default packages provided by JavaSE 1.6 environment. All OSGi
	 * bundles can use JavaSE packages naturally by doing this. Some OSGi and
	 * logging related packages also included.
	 * 
	 * @return the whole concatenated list of system packages.
	 * @throws FileNotFoundException
	 */
	private String getSystemPackages() throws FileNotFoundException {
		StringBuffer buffer = new StringBuffer(4096);
		BufferedReader reader = new BufferedReader(new InputStreamReader(
				ClassLoader.getSystemResourceAsStream("system.packages")));
		String s = null;
		try {
			while ((s = reader.readLine()) != null) {
				buffer.append(s);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}

		return buffer.toString();
	}

	/**
	 * Bind telnet port.
	 * 
	 * @throws IOException
	 */
	private void openConsolePort(BundleContext bc) throws IOException {
		InetAddress address = getConsoleBindAddress();
		int port = getConsolePortNumber();
		InetSocketAddress bindSocketAddress = new InetSocketAddress(address, port);

		NioSocketAcceptor acceptor = new NioSocketAcceptor();
		acceptor.getFilterChain().addLast("protocol", new ProtocolCodecFilter(new TelnetCodecFactory()));
		acceptor.setHandler(new TelnetHandler(bc));
		acceptor.setReuseAddress(true);
		acceptor.bind(bindSocketAddress);
		logger.info("Console " + bindSocketAddress + " opened.");
	}

	/**
	 * Return localhost address by default. Use kraken.bind.address system
	 * property if you need to connect telnet server from remote host.
	 * 
	 * @return the bind address
	 * @throws UnknownHostException
	 */
	private InetAddress getConsoleBindAddress() throws UnknownHostException {
		String bindAddress = System.getProperty("kraken.bind.address");
		if (bindAddress == null)
			return InetAddress.getByName("localhost");

		return InetAddress.getByName(bindAddress);
	}

	/**
	 * 
	 * @return the telnet port number
	 */
	private int getConsolePortNumber() {
		int consolePort = 7004;
		try {
			consolePort = Integer.parseInt((String) System.getProperty("kraken.port"));
		} catch (Exception e) {
			// ignore
		}

		return consolePort;
	}

	/*************************************************
	 * BundleActivator interfaces
	 *************************************************/

	/**
	 * Start system bundle.
	 */
	@Override
	public void start(BundleContext context) throws Exception {
		Kraken.context = context;

		logger.info("Booting Kraken.");
		startLogging();
		setBanner();

		prefsManager = new PreferencesManager();
		prefsManager.start(context);

		registerScripts(context);
		registerInstrumentation();
		openConsolePort(context);
		startSshServer();
		logger.info("Kraken started.");
	}

	private void setBanner() throws IOException {
		try {
			String jarFileName = System.getProperty("java.class.path").split(System.getProperty("path.separator"))[0];
			JarFile jar = new JarFile(jarFileName);
			Manifest mf = jar.getManifest();
			Attributes attrs = mf.getMainAttributes();
			BANNER = "Kraken (version " + attrs.getValue("Kraken-Version") + ")";
		} catch (FileNotFoundException e) {
			BANNER = "Kraken (Debug mode)";
		}
	}

	/**
	 * Register default kraken scripts.
	 * 
	 * @see Kraken API documentation
	 * @param context
	 *            the bundle context
	 */
	private void registerScripts(BundleContext context) {
		registerScriptFactory(context, CoreScriptFactory.class, "core");
		registerScriptFactory(context, BundleScriptFactory.class, "bundle");
		registerScriptFactory(context, LoggerScriptFactory.class, "logger");
		registerScriptFactory(context, OsgiScriptFactory.class, "osgi");
		registerScriptFactory(context, PackageScriptFactory.class, "pkg");
		registerScriptFactory(context, BatchScriptFactory.class, "batch");
		registerScriptFactory(context, HistoryScriptFactory.class, "history");
		registerScriptFactory(context, ThreadScriptFactory.class, "thread");
		registerScriptFactory(context, PerfScriptFactory.class, "perf");
		registerScriptFactory(context, RegistryScriptFactory.class, "registry");
		registerScriptFactory(context, KeyStoreScriptFactory.class, "keystore");
		registerScriptFactory(context, AccountScriptFactory.class, "account");
		registerScriptFactory(context, SunPerfScriptFactory.class, "sunperf");
	}

	/**
	 * Register script factory to OSGi service registry.
	 * 
	 * @param context
	 *            the bundle context
	 * @param scriptFactory
	 *            the script factory
	 * @param alias
	 *            the script alias (e.g. logger is alias in "logger.list"
	 *            command)
	 */
	private void registerScriptFactory(BundleContext context, Class<? extends ScriptFactory> scriptFactory, String alias) {
		Dictionary<String, Object> props = new Hashtable<String, Object>();
		props.put("alias", alias);
		try {
			context.registerService(ScriptFactory.class.getName(), scriptFactory.newInstance(), props);
		} catch (InstantiationException e) {
			e.printStackTrace();
		} catch (IllegalAccessException e) {
			e.printStackTrace();
		}
	}

	private void registerInstrumentation() {
		context.registerService(InstrumentationService.class.getName(), new InstrumentationServiceImpl(), null);
	}

	/**
	 * Stop system bundle.
	 */
	@Override
	public void stop(BundleContext context) throws Exception {
		prefsManager.stop(context);

		stopLogging();

		if (!serviceMode)
			System.exit(0);
	}

	/**
	 * Register OSGi log service to OSGi service registry, and start logging
	 * thread. Logging thread will log using log4j logger and pass all logs to
	 * connected log monitor.
	 */
	private void startLogging() {
		context.registerService(new String[] { LogService.class.getName(), LoggerControlService.class.getName() },
				new KrakenLogService(), null);

		KrakenLoggerFactory krakenLoggerFactory = (KrakenLoggerFactory) StaticLoggerBinder.getSingleton()
				.getLoggerFactory();
		krakenLoggerFactory.start();

		enforceLogLevel("httpclient.wire");
		enforceLogLevel("org.apache.commons.httpclient");
	}

	private void enforceLogLevel(String loggerName) {
		org.apache.log4j.Logger logger = org.apache.log4j.Logger.getLogger(loggerName);
		logger.setLevel(Level.INFO);
	}

	/**
	 * Stop the logging thread.
	 */
	private void stopLogging() {
		KrakenLoggerFactory krakenLoggerFactory = (KrakenLoggerFactory) StaticLoggerBinder.getSingleton()
				.getLoggerFactory();
		krakenLoggerFactory.stop();
	}

	private void setDefaultLogging() throws IOException {
		org.apache.log4j.Logger rootLogger = org.apache.log4j.Logger.getRootLogger();
		if (!rootLogger.getAllAppenders().hasMoreElements()) {
			SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss,SSS");
			System.out.println(String.format("[%s]  INFO (Kraken) - Default logging enabled. "
					+ "Configure log4j.properties file for custom logging.", dateFormat.format(new Date())));

			String logPath = new File(System.getProperty("kraken.log.dir"), "kraken.log").getAbsolutePath();
			rootLogger.setLevel(Level.DEBUG);
			PatternLayout layout = new PatternLayout("[%d] %5p (%c{1}) - %m%n");
			rootLogger.addAppender(new ConsoleAppender(layout));
			rootLogger.addAppender(new DailyRollingFileAppender(layout, logPath, ".yyyy-MM-dd"));

			logCleaner = new Thread(new LogCleaner(), "Kraken Log Cleaner");
			logCleaner.start();
		}
	}

	private void startSshServer() throws IOException {
		org.apache.log4j.Logger sshLogger = org.apache.log4j.Logger
				.getLogger("org.apache.sshd.server.session.ServerSession");
		sshLogger.setLevel(Level.WARN);

		int port = 7022;
		try {
			port = Integer.parseInt((String) System.getProperty("kraken.ssh.port"));
		} catch (Exception e) {
			// ignore
		}

		SshServer sshd = SshServer.setUpDefaultServer();
		sshd.setPort(port);
		sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider("hostkey.pem"));
		sshd.setShellFactory(new SshCommandFactory());
		sshd.setPasswordAuthenticator(new SshPasswordAuthenticator());
		sshd.start();
	}
}
