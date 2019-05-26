package io.ejat.framework;

import java.net.MalformedURLException;
import java.net.URL;
import java.time.Instant;
import java.util.Properties;

import javax.validation.constraints.NotNull;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.felix.bundlerepository.Reason;
import org.apache.felix.bundlerepository.RepositoryAdmin;
import org.apache.felix.bundlerepository.Resolver;
import org.apache.felix.bundlerepository.Resource;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import dev.cirillo.maven.repository.IMavenRepository;
import io.ejat.framework.spi.AbstractManager;
import io.ejat.framework.spi.DynamicStatusStoreException;
import io.ejat.framework.spi.FrameworkException;
import io.ejat.framework.spi.IConfigurationPropertyStoreService;
import io.ejat.framework.spi.IDynamicStatusStoreService;
import io.ejat.framework.spi.IFramework;
import io.ejat.framework.spi.IRun;

/**
 * Run the supplied test class
 */
@Component(service={TestRunner.class})
public class TestRunner {


	private Log logger = LogFactory.getLog(TestRunner.class);

	private BundleContext bundleContext;

	@Reference
	private RepositoryAdmin repositoryAdmin;

	@Reference
	private IMavenRepository mavenRepository;

	private TestRunHeartbeat heartbeat;

	private IConfigurationPropertyStoreService cps;
	private IDynamicStatusStoreService dss;
	private IRun                       run;

	/**
	 * Run the supplied test class
	 * 
	 * @param testBundleName
	 * @param testClassName
	 * @return
	 * @throws TestRunException
	 */
	public void runTest(Properties bootstrapProperties, Properties overrideProperties) throws TestRunException  {

		//*** Initialise the framework services
		FrameworkInitialisation frameworkInitialisation = null;
		try {
			frameworkInitialisation = new FrameworkInitialisation(bootstrapProperties, overrideProperties);
			cps = frameworkInitialisation.getFramework().getConfigurationPropertyService("framework");
			dss = frameworkInitialisation.getFramework().getDynamicStatusStoreService("framework");
			run = frameworkInitialisation.getFramework().getTestRun();
		} catch (Exception e) {
			throw new TestRunException("Unable to initialise the Framework Services", e);
		}

		IRun run = frameworkInitialisation.getFramework().getTestRun();

		String testBundleName = run.getTestBundleName();
		String testClassName = run.getTestClassName();

		String testRepository = null;
		String testOBR        = null;
		String stream         = AbstractManager.nulled(run.getStream());

		if (stream != null) {
			logger.debug("Loading test stream " + stream);
			try {
				testRepository = this.cps.getProperty("stream", "repo", stream);
				testOBR        = this.cps.getProperty("stream", "obr", stream);
			} catch(Exception e) {
				logger.error("Unable to load stream " + stream + " settings",e);
				updateStatus("finished", "finished");
				return;
			}
		}

		String overrideRepo = AbstractManager.nulled(run.getRepository());
		if (overrideRepo != null) {
			testRepository = overrideRepo;
		}
		String overrideOBR = AbstractManager.nulled(run.getOBR());
		if (overrideOBR != null) {
			testOBR = overrideOBR;
		}

		if (testRepository != null) {
			logger.debug("Loading test maven repository " + testRepository);
			try {
				this.mavenRepository.addRemoteRepository(new URL(testRepository));
			} catch (MalformedURLException e) {
				logger.error("Unable to add remote maven repository " + testRepository,e);
				updateStatus("finished", "finished");
				return;
			}
		}

		if (testOBR != null) {
			logger.debug("Loading test obr repository " + testOBR);
			try {
				repositoryAdmin.addRepository(testOBR);
			} catch (Exception e) {
				logger.error("Unable to load specified OBR " + testOBR,e);
				updateStatus("finished", "finished");
				return;
			}
		}

		try {
			loadBundle(testBundleName);
		} catch(Exception e) {
			logger.error("Unable to load the test bundle " + testBundleName,e);
			updateStatus("finished", "finished");
			return;
		}

		try {
			heartbeat = new TestRunHeartbeat(frameworkInitialisation.getFramework());
			heartbeat.start();
		} catch (DynamicStatusStoreException e1) {
			throw new TestRunException("Unable to initialise the heartbeat");
		}

		updateStatus("started", "started");

		logger.info("Run test: " + testBundleName + "/" + testClassName);
		Class<?> testClass = getTestClass(testBundleName, testClassName);

		//*** Initialise the Managers ready for the test run
		TestRunManagers managers = null;
		try {
			managers = new TestRunManagers(frameworkInitialisation.getFramework(), testClass);
		} catch (FrameworkException e) {
			stopHeartbeat();
			throw new TestRunException("Problem initialising the Managers for a test run", e);
		}

		try {
			if (managers.anyReasonTestClassShouldBeIgnored()) {
				stopHeartbeat();
				updateStatus("finished", "finished");
				return; //TODO handle ignored classes
			}
		} catch (FrameworkException e) {
			throw new TestRunException("Problem asking Managers for an ignore reason", e);
		}


		TestClassWrapper testClassWrapper = new TestClassWrapper(testBundleName, testClass);

		testClassWrapper.parseTestClass();

		testClassWrapper.instantiateTestClass();



		try {
			updateStatus("generating", null);
			managers.provisionGenerate();
		} catch(Exception e) {
			stopHeartbeat();
			throw new TestRunException("Unable to provision generate", e);
		}

		try {
			updateStatus("building", null);
			managers.provisionBuild();
		} catch(Exception e) {
			managers.provisionDiscard();
			stopHeartbeat();
			throw new TestRunException("Unable to provision build", e);
		}

		try {
			updateStatus("provstart", null);
			managers.provisionStart();
		} catch(Exception e) {
			managers.provisionStop();
			managers.provisionDiscard();
			stopHeartbeat();
			throw new TestRunException("Unable to provision start", e);
		}

		updateStatus("running", null);
		testClassWrapper.runTestMethods(managers);

		updateStatus("stopping", null);
		managers.provisionStop();
		updateStatus("discarding", null);
		managers.provisionDiscard();
		updateStatus("ending", null);
		managers.endOfTestRun();
		stopHeartbeat();
		updateStatus("finished", "finished");

		//*** If this was a local run, then we will want to remove the run properties from the DSS immediately
		//*** for automation, we will let the core manager clean up after a while
		//*** Local runs will have access to the run details via a view,
		//*** But automation runs will only exist in the RAS if we delete them, so need to give 
		//*** time for things like jenkins and other run requesters to obtain the result and RAS id before 
		//*** deleting,  default is to keep the automation run properties for 5 minutes
		try {
			deleteRunProperties(frameworkInitialisation.getFramework());
		} catch (FrameworkException e) {
			//*** Any error, report it and leave for the core manager to clean up
			logger.error("Error cleaning up local test run properties", e);
		}

		return;
	}

	private void updateStatus(String status, String timestamp) throws TestRunException {
		try {
			this.dss.put("run." + run.getName() + ".status", status);
			if (timestamp != null) {
				this.dss.put("run." + run.getName() + "." + timestamp, Instant.now().toString());
			}
		} catch (DynamicStatusStoreException e) {
			throw new TestRunException("Failed to update status", e);
		}
	}

	private void stopHeartbeat() {
		heartbeat.shutdown();
		try {
			heartbeat.join(2000);
		} catch(Exception e) {
		}
	}


	private void deleteRunProperties(@NotNull IFramework framework) throws FrameworkException {

		IRun run = framework.getTestRun();

		if (!run.isLocal()) { //*** Not interested in non-local runs
			return;
		}

		framework.getFrameworkRuns().delete(run.getName());
	}


	/**
	 * Get the test class from the supplied bundle
	 * 
	 * @param testBundleName
	 * @param testClassName
	 * @return
	 * @throws TestRunException
	 */
	private Class<?> getTestClass(String testBundleName, String testClassName) throws TestRunException {
		Class<?> testClazz = null;
		Bundle[] bundles = bundleContext.getBundles();
		boolean bundleFound = false;
		for (Bundle bundle : bundles) {
			if (bundle.getSymbolicName().equals(testBundleName)) {
				bundleFound = true;
				logger.trace("Found Bundle: " + testBundleName);
				try {
					testClazz = bundle.loadClass(testClassName);
				} catch (ClassNotFoundException e) {
					throw new TestRunException("Unable to load test class " + testClassName, e);
				}
				logger.trace("Found test class: " + testClazz.getName());

				break;
			}
		}
		if (!bundleFound) {
			throw new TestRunException("Unable to find test bundle " + testBundleName);
		}
		return testClazz;		
	}


	@Activate
	public void activate(BundleContext context) {
		this.bundleContext = context;
	}


	/**
	 * Load a bundle from the OSGi Bundle Repository
	 * 
	 * @param bundleSymbolicName
	 * @throws LauncherException 
	 */
	private void loadBundle(String bundleSymbolicName) throws TestRunException {

		logger.trace("Installing bundle " + bundleSymbolicName);
		Resolver resolver = repositoryAdmin.resolver();
		String filterString = "(symbolicname=" + bundleSymbolicName + ")";
		Resource[] resources = null;
		try {
			resources = repositoryAdmin.discoverResources(filterString);
		} catch (InvalidSyntaxException e) {
			throw new TestRunException("Unable to discover repoistory resources", e);
		}
		try {
			if (resources.length == 0) {
				throw new TestRunException("Unable to locate bundle \"" + bundleSymbolicName + "\" in OBR repository");
			}
			for (Resource resource : resources) {
				addResource(bundleSymbolicName, resolver, resource);
			}
		} catch (TestRunException e) {
			throw new TestRunException("Unable to install bundle \"" + bundleSymbolicName + "\" from OBR repository", e);
		}
	}

	/**
	 * Add the Resource to the Resolver and resolve
	 * 
	 * @param bundleSymbolicName
	 * @param resolver
	 * @param resource
	 * @throws LauncherException
	 */
	private void addResource(String bundleSymbolicName, Resolver resolver, Resource resource) throws TestRunException {
		logger.trace("Resouce: " + resource);
		resolver.add(resource);
		if (resolver.resolve())
		{
			if (logger.isTraceEnabled()) {
				Resource[] requiredResources = resolver.getRequiredResources();
				for (Resource requiredResource : requiredResources) {
					logger.trace("  RequiredResource: " + requiredResource.getSymbolicName());
				}
				Resource[] optionalResources = resolver.getOptionalResources();
				for (Resource optionalResource : optionalResources) {
					logger.trace("  OptionalResource: " + optionalResource.getSymbolicName());
				}
			}

			resolver.deploy(Resolver.START);

			if (!isBundleActive(bundleSymbolicName)) {
				throw new TestRunException("Bundle failed to install and activate");
			}

			printBundles();
		}
		else
		{
			Reason[] unsatisfiedRequirements = resolver.getUnsatisfiedRequirements();
			for (Reason reason : unsatisfiedRequirements)
			{
				logger.error(resource.toString() + ": Unable to resolve: " + reason.getRequirement());
			}
			throw new TestRunException("Unable to resolve bundle " + bundleSymbolicName);
		}

	}

	/**
	 * Is the supplied active in the OSGi framework
	 * 
	 * @param bundleSymbolicName
	 * @return true or false
	 */
	private boolean isBundleActive(String bundleSymbolicName) {
		Bundle[] bundles = this.bundleContext.getBundles();
		for (Bundle bundle : bundles) {
			if (bundle.getSymbolicName().equals(bundleSymbolicName) && bundle.getState() == Bundle.ACTIVE) {
				return true;
			}
		}

		return false;
	}


	/** 
	 * Print the currently installed bundles and their state 
	 */
	private void printBundles() {
		if (!logger.isTraceEnabled()) {
			return;
		}
		// Get the bundles
		Bundle[] bundles = this.bundleContext.getBundles();
		// Format and print the bundle Id, State, Symbolic name and Version.
		StringBuilder messageBuffer = new StringBuilder(2048);
		messageBuffer.append("Bundle status:");

		for (Bundle bundle : bundles) {
			String bundleId = String.valueOf(bundle.getBundleId());
			messageBuffer.append("\n").
			append(String.format("%5s", bundleId)).
			append("|").
			append(String.format("%-11s", getBundleStateLabel(bundle))).
			append("|     |").
			append(bundle.getSymbolicName()).
			append(" (").
			append(bundle.getVersion()).
			append(")");
		}

		logger.trace(messageBuffer.toString());
	}

	/**
	 * Convert bundle state to string
	 * @param bundle
	 * @return The bundle state
	 */
	private String getBundleStateLabel(Bundle bundle) {
		switch (bundle.getState()) {
		case Bundle.UNINSTALLED: return "Uninstalled";
		case Bundle.INSTALLED: return "Installed";
		case Bundle.RESOLVED: return "Resolved";
		case Bundle.STARTING: return "Starting";
		case Bundle.STOPPING: return "Stopping";
		case Bundle.ACTIVE: return "Active";
		default: return "<Unknown (" + bundle.getState() + ")>";
		}
	}


}