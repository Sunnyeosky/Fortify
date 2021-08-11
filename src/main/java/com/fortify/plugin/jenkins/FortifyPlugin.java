/*******************************************************************************
 * (c) Copyright 2020 Micro Focus or one of its affiliates.
 * 
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * https://opensource.org/licenses/MIT
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package com.fortify.plugin.jenkins;

import java.io.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.CheckForNull;

import com.fortify.plugin.jenkins.bean.SensorPoolBean;
import com.fortify.plugin.jenkins.steps.*;
import com.fortify.plugin.jenkins.steps.remote.GradleProjectType;
import com.fortify.plugin.jenkins.steps.remote.MavenProjectType;
import com.fortify.plugin.jenkins.steps.remote.RemoteAnalysisProjectType;
import com.squareup.okhttp.*;
import hudson.*;
import hudson.model.*;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.*;
import org.kohsuke.stapler.verb.POST;

import com.fortify.plugin.jenkins.bean.ProjectTemplateBean;
import com.fortify.plugin.jenkins.fortifyclient.FortifyClient;
import com.fortify.plugin.jenkins.fortifyclient.FortifyClient.NoReturn;
import com.fortify.plugin.jenkins.steps.types.AdvancedScanType;
import com.fortify.plugin.jenkins.steps.types.DevenvScanType;
import com.fortify.plugin.jenkins.steps.types.DotnetSourceScanType;
import com.fortify.plugin.jenkins.steps.types.GradleScanType;
import com.fortify.plugin.jenkins.steps.types.JavaScanType;
import com.fortify.plugin.jenkins.steps.types.MavenScanType;
import com.fortify.plugin.jenkins.steps.types.MsbuildScanType;
import com.fortify.plugin.jenkins.steps.types.OtherScanType;
import com.fortify.plugin.jenkins.steps.types.ProjectScanType;
import com.fortify.ssc.restclient.ApiException;

import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.util.ComboBoxModel;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import jenkins.model.Jenkins;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;

/**
 * Fortify Jenkins plugin to work with Fortify Software Security Center and
 * Fortify Static Code Analyzer
 * 
 * <p>
 * Main plugin functionality:
 * <ul>
 * <li>Provide pipeline and other means to launch Fortify Static Code Analysis
 * (SCA) as part of the build</li>
 * <li>Upload the resulting FPR analysis file to Fortify Software Security
 * Center (SSC) server</li>
 * <li>Calculate NVS from the results collected from SSC and plot graph on the
 * project main page</li>
 * <li>Make a build to be UNSTABLE if some critical vulnerabilities are found
 * (or based on other info from SSC)</li>
 * <li>Display detailed list of vulnerabilities collected from SSC and provide
 * remediation links</li>
 * </ul>
 *
 */
public class FortifyPlugin extends Recorder {
	private static String pluginVersion;

	public static String getPluginVersion() {
		if (pluginVersion == null) {
			Plugin plugin = Jenkins.get().getPlugin("fortify");
			if (plugin != null) {
				pluginVersion = plugin.getWrapper().getVersion();
			}
		}
		return pluginVersion;
	}

	private static final Logger LOGGER = Logger.getLogger(FortifyPlugin.class.getName());

	private static Object syncObj = new Object();

	public static final int DEFAULT_PAGE_SIZE = 50;
	public static final int DEFAULT_DROPDOWN_LIMIT = 100;

	private transient UploadSSCBlock uploadSSC;
	private transient RunTranslationBlock runTranslation;
	private transient RunScanBlock runScan;
	private transient UpdateContentBlock updateContent;
	private transient boolean runSCAClean;
	private transient String buildId;
	private transient String scanFile;
	private transient Integer maxHeap;
	private transient String addJVMOptions;

	private AnalysisRunType analysisRunType;

	@DataBoundConstructor
	public FortifyPlugin(AnalysisRunType analysisRunType) {
		this.analysisRunType = analysisRunType;
	}

	@Deprecated
	public FortifyPlugin(String buildId, String scanFile, Integer maxHeap, String addJVMOptions,
			UpdateContentBlock updateContent, boolean runSCAClean, RunTranslationBlock runTranslation,
			RunScanBlock runScan, UploadSSCBlock uploadSSC) {
		this.buildId = buildId;
		this.scanFile = scanFile;
		this.maxHeap = maxHeap;
		this.addJVMOptions = addJVMOptions;
		this.updateContent = updateContent;
		this.runSCAClean = runSCAClean;
		this.runTranslation = runTranslation;
		this.runScan = runScan;
		this.uploadSSC = uploadSSC;
	}

	/* for backwards compatibility */
	protected Object readResolve() {
		if (runTranslation != null) {
			analysisRunType = new AnalysisRunType("local");
			if (updateContent != null) {
				analysisRunType.setUpdateContent(updateContent);
			}

			if (buildId != null) {
				analysisRunType.setBuildId(buildId);
			}
			if (scanFile != null) {
				analysisRunType.setScanFile(scanFile);
			}
			if (maxHeap != null) {
				analysisRunType.setMaxHeap(maxHeap);
			}
			if (addJVMOptions != null) {
				analysisRunType.setAddJVMOptions(addJVMOptions);
			}

			analysisRunType.setTranslationDebug(runTranslation.getTranslationDebug());
			analysisRunType.setTranslationVerbose(runTranslation.getTranslationVerbose());
			analysisRunType.setTranslationLogFile(runTranslation.getTranslationLogFile());
			analysisRunType.setTranslationExcludeList(runTranslation.getTranslationExcludeList());

			ProjectScanType scanType = null;
			if (runTranslation.isAdvancedTranslationType()) {
				scanType = new AdvancedScanType();
				((AdvancedScanType)scanType).setAdvOptions(runTranslation.getTranslationOptions());
			}

			if (runTranslation.isBasicMaven3TranslationType()) {
				scanType = new MavenScanType();
				((MavenScanType)scanType).setMavenOptions(runTranslation.getMaven3Options());
			}

			if (runTranslation.isBasicGradleTranslationType()) {
				scanType = new GradleScanType();
				((GradleScanType)scanType).setUseWrapper(runTranslation.getGradleUseWrapper());
				((GradleScanType)scanType).setGradleTasks(runTranslation.getGradleTasks());
				((GradleScanType)scanType).setGradleOptions(runTranslation.getGradleOptions());
			}

			if (runTranslation.isBasicJavaTranslationType()) {
				scanType = new JavaScanType();
				((JavaScanType)scanType).setJavaVersion(runTranslation.getTranslationJavaVersion());
				((JavaScanType)scanType).setJavaClasspath(runTranslation.getTranslationClasspath());
				((JavaScanType)scanType).setJavaSrcFiles(runTranslation.getTranslationSourceFiles());
				((JavaScanType)scanType).setJavaAddOptions(runTranslation.getTranslationAddOptions());
			}

			if (runTranslation.isBasicDotNetDevenvBuildType()) {
				scanType = new DevenvScanType();
				((DevenvScanType)scanType).setDotnetProject(runTranslation.getDotNetDevenvProjects());
				((DevenvScanType)scanType).setDotnetAddOptions(runTranslation.getDotNetDevenvAddOptions());
			}

			if (runTranslation.isBasicDotNetMSBuildBuildType()) {
				scanType = new MsbuildScanType();
				((MsbuildScanType)scanType).setDotnetProject(runTranslation.getDotNetMSBuildProjects());
				((MsbuildScanType)scanType).setDotnetAddOptions(runTranslation.getDotNetMSBuildAddOptions());
			}

			if (runTranslation.isBasicDotNetSourceCodeScanType()) {
				scanType = new DotnetSourceScanType();
				((DotnetSourceScanType)scanType).setDotnetFrameworkVersion(runTranslation.getDotNetSourceCodeFrameworkVersion());
				((DotnetSourceScanType)scanType).setDotnetLibdirs(runTranslation.getDotNetSourceCodeLibdirs());
				((DotnetSourceScanType)scanType).setDotnetSrcFiles(runTranslation.getDotNetSourceCodeSrcFiles());
				((DotnetSourceScanType)scanType).setDotnetAddOptions(runTranslation.getDotNetSourceCodeAddOptions());
			}

			if (runTranslation.isBasicOtherTranslationType()) {
				scanType = new OtherScanType();
				((OtherScanType)scanType).setOtherIncludesList(runTranslation.getOtherIncludesList());
				((OtherScanType)scanType).setOtherOptions(runTranslation.getOtherOptions());
			}

			if (scanType != null) {
				analysisRunType.setProjectScanType(scanType);
			}

		}
		if (runScan != null) {
			analysisRunType.setRunScan(runScan);
		}
		if (uploadSSC != null) {
			analysisRunType.setUploadSSC(uploadSSC);
		}
		return this;
	}

	public boolean getAnalysisRunType() { return analysisRunType != null; }

	// populate the dropdown lists
	public RemoteAnalysisProjectType getRemoteAnalysisProjectType() { return getAnalysisRunType() ? analysisRunType.getRemoteAnalysisProjectType() : null; }
	public ProjectScanType getProjectScanType() { return getAnalysisRunType() ? analysisRunType.getProjectScanType() : null; }

	public boolean isRemote() {
		return getAnalysisRunType() && analysisRunType.value.equals("remote");
	}

	public boolean isMixed() {
		return getAnalysisRunType() && analysisRunType.value.equals("mixed");
	}

	public boolean isLocal() {
		return getAnalysisRunType() && analysisRunType.value.equals("local");
	}

	public boolean isUploadOnly() {
		return getAnalysisRunType() && analysisRunType.value.equals("uploadOnly");
	}

	public boolean isTranslationDebug() { return getAnalysisRunType() && analysisRunType.isTranslationDebug(); }
	public boolean isTranslationVerbose() { return getAnalysisRunType() && analysisRunType.isTranslationVerbose(); }

	public String getBuildId() {
		return getAnalysisRunType() ? analysisRunType.getBuildId() : "";
	}

	public String getScanFile() {
		return getAnalysisRunType() ? analysisRunType.getScanFile() : "";
	}

	public Integer getMaxHeap() {
		return getAnalysisRunType() ? analysisRunType.getMaxHeap() : null;
	}

	public String getAddJVMOptions() {
		return getAnalysisRunType() ? analysisRunType.getAddJVMOptions() : "";
	}

	public boolean getUpdateContent() {
		return getAnalysisRunType() && analysisRunType.getUpdateContent() != null;
	}

	@Deprecated
	public boolean getRunTranslation() {
		return runTranslation != null;
	}

	public boolean getRunScan() {
		return getAnalysisRunType() && analysisRunType.getRunScan() != null;
	}

	public boolean getUploadSSC() {
		return getAnalysisRunType() && analysisRunType.getUploadSSC() != null;
	}

	public String getUpdateServerUrl() {
		return getUpdateContent() ? analysisRunType.getUpdateContent().getUpdateServerUrl() : "";
	}

	public String getLocale() {
		return getUpdateContent() ? analysisRunType.getUpdateContent().getLocale() : "";
	}

	@Deprecated
	public boolean getUpdateUseProxy() {
		return getUpdateContent() && updateContent.getUpdateUseProxy();
	}

	@Deprecated
	public String getUpdateProxyUrl() {
		return getUpdateUseProxy() ? updateContent.getUpdateProxyUrl() : "";
	}

	@Deprecated
	public String getUpdateProxyUsername() {
		return getUpdateUseProxy() ? updateContent.getUpdateProxyUsername() : "";
	}

	@Deprecated
	public String getUpdateProxyPassword() {
		return getUpdateUseProxy() ? updateContent.getUpdateProxyPassword() : "";
	}

	@Deprecated
	public String getTranslationType() {
		return getRunTranslation() ? runTranslation.getTranslationType() : "";
	}

	@Deprecated
	public boolean getIsBasicTranslationType() {
		return getRunTranslation() && runTranslation.isBasicTranslationType();
	}

	@Deprecated
	public boolean getIsAdvancedTranslationType() {
		return getRunTranslation() && runTranslation.isAdvancedTranslationType();
	}

	@Deprecated
	public boolean getIsBasicJavaTranslationType() {
		return getRunTranslation() && runTranslation.isBasicJavaTranslationType();
	}

	@Deprecated
	public boolean getIsBasicDotNetTranslationType() {
		return getRunTranslation() && runTranslation.isBasicDotNetTranslationType();
	}

	@Deprecated
	public boolean getIsBasicMaven3TranslationType() {
		return getRunTranslation() && runTranslation.isBasicMaven3TranslationType();
	}

	@Deprecated
	public boolean getIsBasicGradleTranslationType() {
		return getRunTranslation() && runTranslation.isBasicGradleTranslationType();
	}

	@Deprecated
	public boolean getIsBasicOtherTranslationType() {
		return getRunTranslation() && runTranslation.isBasicOtherTranslationType();
	}

	@Deprecated
	public String getTranslationJavaVersion() {
		return getRunTranslation() ? runTranslation.getTranslationJavaVersion() : "";
	}

	@Deprecated
	public String getTranslationJavaClasspath() {
		return getRunTranslation() ? runTranslation.getTranslationClasspath() : "";
	}

	@Deprecated
	public String getTranslationJavaSourceFiles() {
		return getRunTranslation() ? runTranslation.getTranslationSourceFiles() : "";
	}

	@Deprecated
	public String getTranslationJavaAddOptions() {
		return getRunTranslation() ? runTranslation.getTranslationAddOptions() : "";
	}

	public String getTranslationExcludeList() {
		return getAnalysisRunType() ? analysisRunType.getTranslationExcludeList() : "";
	}

	@Deprecated
	public String getTranslationOptions() {
		return getRunTranslation() ? runTranslation.getTranslationOptions() : "";
	}

	@Deprecated
	public boolean getTranslationDebug() {
		return getRunTranslation() && runTranslation.getTranslationDebug();
	}

	@Deprecated
	public boolean getTranslationVerbose() {
		return getRunTranslation() && runTranslation.getTranslationVerbose();
	}

	public String getTranslationLogFile() {
		return getAnalysisRunType() ? analysisRunType.getTranslationLogFile() : "";
	}

	@Deprecated
	public boolean getIsBasicDotNetProjectSolutionScanType() {
		return getRunTranslation() && runTranslation.isBasicDotNetProjectSolutionScanType();
	}

	@Deprecated
	public boolean getIsBasicDotNetSourceCodeScanType() {
		return getRunTranslation() && runTranslation.isBasicDotNetSourceCodeScanType();
	}

	@Deprecated
	public boolean getIsBasicDotNetDevenvBuildType() {
		return getRunTranslation() && runTranslation.isBasicDotNetDevenvBuildType();
	}

	@Deprecated
	public boolean getIsBasicDotNetMSBuildBuildType() {
		return getRunTranslation() && runTranslation.isBasicDotNetMSBuildBuildType();
	}

	@Deprecated
	public String getDotNetDevenvProjects() {
		return getRunTranslation() ? runTranslation.getDotNetDevenvProjects() : "";
	}

	@Deprecated
	public String getDotNetDevenvAddOptions() {
		return getRunTranslation() ? runTranslation.getDotNetDevenvAddOptions() : "";
	}

	@Deprecated
	public String getDotNetMSBuildProjects() {
		return getRunTranslation() ? runTranslation.getDotNetMSBuildProjects() : "";
	}

	@Deprecated
	public String getDotNetMSBuildAddOptions() {
		return getRunTranslation() ? runTranslation.getDotNetMSBuildAddOptions() : "";
	}

	@Deprecated
	public String getDotNetSourceCodeFrameworkVersion() {
		return getRunTranslation() ? runTranslation.getDotNetSourceCodeFrameworkVersion() : "";
	}

	@Deprecated
	public String getDotNetSourceCodeLibdirs() {
		return getRunTranslation() ? runTranslation.getDotNetSourceCodeLibdirs() : "";
	}

	@Deprecated
	public String getDotNetSourceCodeAddOptions() {
		return getRunTranslation() ? runTranslation.getDotNetSourceCodeAddOptions() : "";
	}

	@Deprecated
	public String getDotNetSourceCodeSrcFiles() {
		return getRunTranslation() ? runTranslation.getDotNetSourceCodeSrcFiles() : "";
	}

	@Deprecated
	public String getMaven3Options() {
		return getRunTranslation() ? runTranslation.getMaven3Options() : "";
	}

	@Deprecated
	public boolean getGradleUseWrapper() {
		return getRunTranslation() && runTranslation.getGradleUseWrapper();
	}

	@Deprecated
	public String getGradleTasks() {
		return getRunTranslation() ? runTranslation.getGradleTasks() : "";
	}

	@Deprecated
	public String getGradleOptions() {
		return getRunTranslation() ? runTranslation.getGradleOptions() : "";
	}

	@Deprecated
	public String getOtherOptions() {
		return getRunTranslation() ? runTranslation.getOtherOptions() : "";
	}

	@Deprecated
	public String getOtherIncludesList() {
		return getRunTranslation() ? runTranslation.getOtherIncludesList() : "";
	}

	public String getScanCustomRulepacks() {
		return getRunScan() ? analysisRunType.getRunScan().getScanCustomRulepacks() : "";
	}

	public String getScanAddOptions() {
		return getRunScan() ? analysisRunType.getRunScan().getScanAddOptions() : "";
	}

	public boolean getScanDebug() {
		return getRunScan() && analysisRunType.getRunScan().getScanDebug();
	}

	public boolean getScanVerbose() {
		return getRunScan() && analysisRunType.getRunScan().getScanVerbose();
	}

	public String getScanLogFile() {
		return getRunScan() ? analysisRunType.getRunScan().getScanLogFile() : "";
	}

	// these are the original fields for uploading to ssc - don't feel like renaming
	// them...
	public String getFilterSet() {
		return getUploadSSC() ? analysisRunType.getUploadSSC().getFilterSet() : "";
	}

	public String getSearchCondition() {
		return getUploadSSC() ? analysisRunType.getUploadSSC().getSearchCondition() : "";
	}

	@Deprecated
	public String getProjectName() {
		return getUploadSSC() ? analysisRunType.getUploadSSC().getProjectName() : "";
	}

	public String getAppName() { return getUploadSSC() ? analysisRunType.getUploadSSC().getAppName() : ""; }

	@Deprecated
	public String getProjectVersion() {
		return getUploadSSC() ? analysisRunType.getUploadSSC().getProjectVersion() : "";
	}

	public String getAppVersion() { return getUploadSSC() ? analysisRunType.getUploadSSC().getAppVersion() : ""; }

	@Deprecated
	public Integer getUploadWaitTime() {
		return uploadSSC == null ? null : uploadSSC.getPollingInterval();
	}

	public Integer getTimeout() {
		return getUploadSSC() ? analysisRunType.getUploadSSC().getTimeout() : null;
	}

	public Integer getPollingInterval() {
		return getUploadSSC() ? analysisRunType.getUploadSSC().getPollingInterval() : null;
	}

	public boolean getRemoteOptionalConfig() {
		return !isLocal() && !isUploadOnly() && analysisRunType.getRemoteOptionalConfig() != null;
	}

	public String getSensorPoolUUID() {
		return getRemoteOptionalConfig() ? analysisRunType.getRemoteOptionalConfig().getSensorPoolUUID() : "";
	}

	public String getNotifyEmail() {
		return getRemoteOptionalConfig() ? analysisRunType.getRemoteOptionalConfig().getNotifyEmail() : "";
	}

	public String getScanOptions() {
		return getRemoteOptionalConfig() ? analysisRunType.getRemoteOptionalConfig().getScanOptions() : "";
	}

	public String getCustomRulepacks() {
		return getRemoteOptionalConfig() ? analysisRunType.getRemoteOptionalConfig().getCustomRulepacks() : "";
	}

	public String getFilterFile() {
		return getRemoteOptionalConfig() ? analysisRunType.getRemoteOptionalConfig().getFilterFile() : "";
	}

	public String getBuildTool() {
		if (!getAnalysisRunType()) {
			return "";
		}
		if (getRemoteAnalysisProjectType() instanceof GradleProjectType) {
			return "gradle";
		} else if (getRemoteAnalysisProjectType() instanceof MavenProjectType) {
			return "mvn";
		} else {
			return "none";
		}
	}

	public String getBuildFile() {
		if (!getAnalysisRunType()) {
			return "";
		}
		if (getRemoteAnalysisProjectType() instanceof GradleProjectType) {
			return ((GradleProjectType) getRemoteAnalysisProjectType()).getBuildFile();
		} else if (getRemoteAnalysisProjectType() instanceof MavenProjectType) {
			return ((MavenProjectType) getRemoteAnalysisProjectType()).getBuildFile();
		} else {
			return "";
		}
	}

	public boolean getIncludeTests() {
		if (getAnalysisRunType()) {
			if (getRemoteAnalysisProjectType() instanceof GradleProjectType) {
				return ((GradleProjectType) getRemoteAnalysisProjectType()).getIncludeTests();
			} else if (getRemoteAnalysisProjectType() instanceof MavenProjectType) {
				return ((MavenProjectType) getRemoteAnalysisProjectType()).getIncludeTests();
			}
		}
		return false;
	}

	public String getTransArgs() {
		return getRemoteAnalysisProjectType() == null ? "" : analysisRunType.getTransArgs();
	}

	public String getScanArgs() { return getRemoteOptionalConfig() ? getScanOptions() : ""; }

	@Override
	public BuildStepMonitor getRequiredMonitorService() {
		return BuildStepMonitor.NONE;
	}

	/*
	 * https://bugzilla.fortify.swinfra.net/bugzilla/show_bug.cgi?id=49956 It is may
	 * be some bad practice to get current opened Jenkins configuration from that
	 * method, but it is not very easy to get that information from other place. The
	 * main problem is that we should store the build (or project) info between
	 * Jenkins starts. If you know how to correctly get project in Publisher without
	 * some manual configuration saving please change it. We should make Fortify
	 * Assessment action as build action not project.
	 */
	@Override
	public Collection<? extends Action> getProjectActions(AbstractProject<?, ?> project) {
		return Collections.emptyList();
	}

	@Override
	public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
			throws InterruptedException, IOException {
		PrintStream log = listener.getLogger();
		log.println("Fortify Jenkins plugin v " + getPluginVersion());

		if (isRemote()) {
			runRemote(build, launcher, listener);
		} else if (isMixed()) {
			runMixed(build, launcher, listener);
		} else if (isLocal()) { // Local Translation
			runLocal(build, launcher, listener);
		} else if (isUploadOnly()) {
			runUploadOnly(build, launcher, listener);
		}

		return true;
	}

	private void runRemote(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
		PrintStream log = listener.getLogger();
		log.println("Running remote translation and scan.");

		final RemoteAnalysisProjectType remoteAnalysisProjectType = getRemoteAnalysisProjectType();
		CloudScanStart csStart = new CloudScanStart(remoteAnalysisProjectType);
		CloudScanArguments csArguments = new CloudScanArguments();

		if (getRemoteOptionalConfig()) {
			csStart.setRemoteOptionalConfig(analysisRunType.getRemoteOptionalConfig());

			csArguments.setScanOptions(getScanArgs());
		}

		csArguments.setTransOptions(getTransArgs());

		if (StringUtils.isNotEmpty(csArguments.getTransOptions()) || StringUtils.isNotEmpty(csArguments.getScanOptions())) {
			csArguments.perform(build, launcher, listener);
		}

		if (getUploadSSC()) {
			csStart.setUploadSSC(analysisRunType.getUploadSSC());
		}

		// run CloudScan start command
		csStart.perform(build, launcher, listener);
	}

	private void runMixed(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
		PrintStream log = listener.getLogger();
		log.println("Running local translation and remote scan.");

		performLocalTranslation(build, launcher, listener);

		CloudScanMbs csMbs = new CloudScanMbs(getBuildId());

		if (getRemoteOptionalConfig()) {
			//csStart.setRemoteOptionalConfig(analysisRunType.getRunRemoteScan().getRemoteOptionalConfig());
			csMbs.setRemoteOptionalConfig(analysisRunType.getRemoteOptionalConfig());
		}

		if (getUploadSSC()) {
			csMbs.setUploadSSC(analysisRunType.getUploadSSC());
		}

		// run CloudScan mbs command
		csMbs.perform(build, launcher, listener);
	}

	private void runLocal(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws IOException, InterruptedException {
		PrintStream log = listener.getLogger();
		log.println("Running local translation and scan.");

		performLocalTranslation(build, launcher, listener);

		if (getRunScan()) {
			if (FortifyPlugin.DESCRIPTOR.isPreventLocalScans()) {
				throw new AbortException(Messages.FortifyScan_Local_NotSupported());
			}
			FortifyScan fs = new FortifyScan(getBuildId());
			fs.setAddJVMOptions(getAddJVMOptions());
			fs.setMaxHeap(getMaxHeap());
			fs.setDebug(getScanDebug());
			fs.setVerbose(getScanVerbose());
			fs.setLogFile(getScanLogFile());
			fs.setResultsFile(getScanFile());
			fs.setCustomRulepacks(getScanCustomRulepacks());
			fs.setAddOptions(getScanAddOptions());

			fs.perform(build, launcher, listener);
		}

		if (getUploadSSC()) {
			FortifyUpload upload = new FortifyUpload(false, getAppName(), getAppVersion());
			upload.setFailureCriteria(getSearchCondition());
			upload.setFilterSet(getFilterSet());
			upload.setResultsFile(getScanFile());
			upload.setTimeout(getTimeout());
			upload.setPollingInterval(getPollingInterval());

			upload.perform(build, launcher, listener);
		}

	}

	private void runUploadOnly(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws IOException, InterruptedException {
		PrintStream log = listener.getLogger();
		log.println("Running upload-only step.");

		FortifyUpload upload = new FortifyUpload(false, getAppName(), getAppVersion());
		upload.setFailureCriteria(getSearchCondition());
		upload.setFilterSet(getFilterSet());
		upload.setResultsFile(getScanFile());
		upload.setPollingInterval(getPollingInterval());

		upload.perform(build, launcher, listener);
	}

	private void performLocalTranslation(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws IOException, InterruptedException {
		// Update security content
		if (getUpdateContent()) {
			FortifyUpdate fu = new FortifyUpdate.Builder().updateServerURL(getUpdateServerUrl()).locale(getLocale()).build();
			fu.perform(build, launcher, listener);
		}
		// run Fortify SCA clean
		FortifyClean fc = new FortifyClean(getBuildId());
		fc.perform(build, launcher, listener);

		final ProjectScanType projectScanType = getProjectScanType();
		if (projectScanType != null) {
			FortifyTranslate ft = new FortifyTranslate(getBuildId(), projectScanType);
			ft.setMaxHeap(getMaxHeap());
			ft.setAddJVMOptions(getAddJVMOptions());
			ft.setDebug(isTranslationDebug());
			ft.setVerbose(isTranslationVerbose());
			ft.setLogFile(getTranslationLogFile());
			ft.setExcludeList(getTranslationExcludeList());

			if (projectScanType instanceof JavaScanType) {
				ft.setJavaVersion(((JavaScanType) projectScanType).getJavaVersion());
				ft.setJavaClasspath(((JavaScanType) projectScanType).getJavaClasspath());
				ft.setJavaSrcFiles(((JavaScanType) projectScanType).getJavaSrcFiles());
				ft.setJavaAddOptions(((JavaScanType) projectScanType).getJavaAddOptions());
			} else if (projectScanType instanceof DevenvScanType) {
				ft.setDotnetProject(((DevenvScanType) projectScanType).getDotnetProject());
				ft.setDotnetAddOptions(((DevenvScanType) projectScanType).getDotnetAddOptions());
			} else if (projectScanType instanceof MsbuildScanType) {
				ft.setDotnetProject(((MsbuildScanType) projectScanType).getDotnetProject());
				ft.setDotnetAddOptions(((MsbuildScanType) projectScanType).getDotnetAddOptions());
			} else if (projectScanType instanceof DotnetSourceScanType) {
				ft.setDotnetFrameworkVersion(((DotnetSourceScanType) projectScanType).getDotnetFrameworkVersion());
				ft.setDotnetLibdirs(((DotnetSourceScanType) projectScanType).getDotnetLibdirs());
				ft.setDotnetAddOptions(((DotnetSourceScanType) projectScanType).getDotnetAddOptions());
				ft.setDotnetSrcFiles(((DotnetSourceScanType) projectScanType).getDotnetSrcFiles());
			} else if (projectScanType instanceof MavenScanType) {
				ft.setMavenOptions(((MavenScanType) projectScanType).getMavenOptions());
			} else if (projectScanType instanceof GradleScanType) {
				ft.setUseWrapper(((GradleScanType) projectScanType).getUseWrapper());
				ft.setGradleTasks(((GradleScanType) projectScanType).getGradleTasks());
				ft.setGradleOptions(((GradleScanType) projectScanType).getGradleOptions());
			} else if (projectScanType instanceof OtherScanType) {
				ft.setOtherIncludesList(((OtherScanType) projectScanType).getOtherIncludesList());
				ft.setOtherOptions(((OtherScanType) projectScanType).getOtherOptions());
			} else if (projectScanType instanceof AdvancedScanType) {
				ft.setAdvOptions(((AdvancedScanType) projectScanType).getAdvOptions());
			}

			ft.perform(build, launcher, listener);
		}
	}

	/**
	 * Determines the {@link ProjectScanType} based on the configuration.
	 */
	private ProjectScanType calculateProjectScanType() {
		if (getIsAdvancedTranslationType()) {
			return new AdvancedScanType();
		} else {
			if (getIsBasicJavaTranslationType()) {
				return new JavaScanType();
			} else if (getIsBasicDotNetTranslationType()) {
				if (getIsBasicDotNetProjectSolutionScanType()) {
					if (getIsBasicDotNetMSBuildBuildType()) {
						return new MsbuildScanType();
					} else {
						return new DevenvScanType();
					}
				} else {
					return new DotnetSourceScanType();
				}
			} else if (getIsBasicMaven3TranslationType()) {
				return new MavenScanType();
			} else if (getIsBasicGradleTranslationType()) {
				return new GradleScanType();
			} else {
				return new OtherScanType();
			}
		}
	}

	private static <T> T runWithFortifyClient(String token, FortifyClient.Command<T> cmd) throws Exception {
		if (cmd != null) {
			String url = DESCRIPTOR.getUrl();
			ClassLoader contextClassLoader = null;
			try {
				FortifyClient client = null;
				synchronized (syncObj) {
					contextClassLoader = Thread.currentThread().getContextClassLoader();
					Thread.currentThread().setContextClassLoader(FortifyPlugin.class.getClassLoader());
					client = new FortifyClient();
					ProxyConfig proxyConfig = DESCRIPTOR.getProxyConfig();
					if (proxyConfig == null || StringUtils.isBlank(proxyConfig.getProxyUrl())) {
						client.init(url, token, DESCRIPTOR.getConnectTimeout(), DESCRIPTOR.getReadTimeout(), DESCRIPTOR.getWriteTimeout());
					} else {
						String proxyUrl = proxyConfig.getProxyUrl();
						String[] proxyUrlSplit = proxyUrl.split(":");
						String proxyHost = proxyUrlSplit[0];
						int proxyPort = 80;
						if (proxyUrlSplit.length > 1) {
							try {
								proxyPort = Integer.parseInt(proxyUrlSplit[1]);
							} catch (NumberFormatException nfe) {
							}
						}
						client.init(url, token, proxyHost, proxyPort, proxyConfig.getProxyUsernameValueOrNull(), proxyConfig.getProxyPasswordValueOrNull(),
								DESCRIPTOR.getConnectTimeout(), DESCRIPTOR.getReadTimeout(), DESCRIPTOR.getWriteTimeout());
					}
				}
				return cmd.runWith(client);
			} finally {
				if (contextClassLoader != null) {
					Thread.currentThread().setContextClassLoader(contextClassLoader);
			}
			}
		}
		return null;
	}

	@Extension
	public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

	@Symbol("fortifyPlugin")
	public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {

		/** SSC URL, e.g. http://localhost:8080/ssc */
		private String url;

		/** @deprecated use {@link #proxyConfig} */
		private transient boolean useProxy = false;
		/** @deprecated use {@link #proxyConfig} */
		private transient String proxyUrl; // host:port
		/** @deprecated use {@link #proxyConfig} */
		private transient Secret proxyUsername;
		/** @deprecated use {@link #proxyConfig} */
		private transient Secret proxyPassword;

		/** SSC proxy */
		@CheckForNull
		private ProxyConfig proxyConfig;

		/** SSC Authentication Token */
		private Secret token;

		/** SSC issue template name (used during creation of new application version) */
		private String projectTemplate;

		/** Number of issues to be displayed per page in breakdown table */
		private Integer breakdownPageSize;

		/** Number of application versions to display in dropdowns */
		private Integer dropdownLimit;

		/** SSC connection timeout */
		private Integer connectTimeout;

		/** SSC read timeout */
		private Integer readTimeout;

		/** SSC write timeout */
		private Integer writeTimeout;

		/** List of Issue Templates obtained from SSC */
		private List<ProjectTemplateBean> projTemplateList = Collections.emptyList();

		/** List of all Projects (including versions info) obtained from SSC */
		private Map<String, Map<String, Long>> allProjects = Collections.emptyMap();

		/** List of all CloudScan Sensor pools obtained from SSC */
		private List<SensorPoolBean> sensorPoolList = Collections.emptyList();

		/** CloudScan Controller URL */
		private String ctrlUrl;

		/** CloudScan Controller Token*/
		private Secret ctrlToken;

		/** Scan Settings */
		private boolean preventLocalScans;

		public DescriptorImpl() {
			super(FortifyPlugin.class);
			load();
		}

		// for backwards compatibility
		private Object readResolve() {
			if (this.useProxy) {
				this.proxyConfig = new ProxyConfig(proxyUrl, proxyUsername, proxyPassword);
			}
			return this;
		}

		@Override
		public boolean isApplicable(Class<? extends AbstractProject> jobType) {
			return true; // applicable to all application type
		}

		@Override
		public String getDisplayName() {
			return "Fortify Assessment";
		}

		public String getUrl() {
			return url;
		}

		@DataBoundSetter
		public void setUrl(String url) {
			try {
				this.url = url == null ? null : url.trim();
				checkUrlValue(this.url);
			} catch (FortifyException e) {
				LOGGER.log(Level.WARNING, "Fortify SSC URL configuration error: " + e.getMessage());
				this.url = null;
			}
		}

		// backwards compatibility
		/** @deprecated use {@link #getProxyConfig()} */
		public boolean getUseProxy() {
			return proxyConfig != null && proxyConfig.getProxyUrl() != null;
		}

		// backwards compatibility
		/** @deprecated use {@link #getProxyConfig()} */
		public String getProxyUrl() {
			return proxyConfig != null ? proxyConfig.getProxyUrl() : null;
		}

		// backwards compatibility
		/** @deprecated use {@link #getProxyConfig()} */
		public String getProxyUsername() {
			return proxyConfig != null ? (proxyConfig.getProxyUsername() != null ? proxyConfig.getProxyUsername().getPlainText() : null) : null;
		}

		// backwards compatibility
		/** @deprecated use {@link #getProxyConfig()} */
		public String getProxyPassword() {
			return proxyConfig != null ? (proxyConfig.getProxyPassword() != null ? proxyConfig.getProxyPassword().getPlainText() : null) : null;
		}

		public ProxyConfig getProxyConfig() {
			return proxyConfig;
		}

		@DataBoundSetter
		public void setProxyConfig(ProxyConfig proxyConfig) {
			this.proxyConfig = proxyConfig;
		}

		public String getToken() {
			return token == null ? "" : token.getPlainText();
		}

		@DataBoundSetter
		public void setToken(String token) {
			this.token = token == null || token.trim().isEmpty() ? null : Secret.fromString(token.trim());
		}

		public boolean canUploadToSsc() {
			return StringUtils.isNotBlank(getUrl()) && StringUtils.isNotBlank(getToken());
		}

		public String getProjectTemplate() {
			return projectTemplate;
		}

		@DataBoundSetter
		public void setProjectTemplate(String projectTemplate) {
			this.projectTemplate = projectTemplate == null ? null : projectTemplate.trim();
		}

		public Integer getBreakdownPageSize() {
			return breakdownPageSize;
		}

		@DataBoundSetter
		public void setBreakdownPageSize(Integer breakdownPageSize) {
			if (breakdownPageSize == null || breakdownPageSize < 1) {
				this.breakdownPageSize = DEFAULT_PAGE_SIZE;
				LOGGER.log(Level.INFO, "Cannot restore 'Issue breakdown page size' property. Will use default (" + DEFAULT_PAGE_SIZE + ") value.");
			} else {
				this.breakdownPageSize = breakdownPageSize;
			}
		}

		public Integer getDropdownLimit() {
			return dropdownLimit;
		}

		@DataBoundSetter
		public void setDropdownLimit(Integer dropdownLimit) {
			if (dropdownLimit == null || dropdownLimit < 1) {
				this.dropdownLimit = DEFAULT_DROPDOWN_LIMIT;
				LOGGER.log(Level.INFO, "Cannot restore 'Application version dropdown limit' property. Will use default (" + DEFAULT_DROPDOWN_LIMIT + ") value.");
			} else {
				this.dropdownLimit = dropdownLimit;
			}
		}

		public Integer getConnectTimeout() {
			return connectTimeout;
		}

		@DataBoundSetter
		public void setConnectTimeout(Integer connectTimeout) {
			this.connectTimeout = connectTimeout;
		}

		public Integer getReadTimeout() {
			return readTimeout;
		}

		@DataBoundSetter
		public void setReadTimeout(Integer readTimeout) {
			this.readTimeout = readTimeout;
		}

		public Integer getWriteTimeout() {
			return writeTimeout;
		}

		@DataBoundSetter
		public void setWriteTimeout(Integer writeTimeout) {
			this.writeTimeout = writeTimeout;
		}

		public String getCtrlUrl() { return ctrlUrl; }

		@DataBoundSetter
		public void setCtrlUrl(String ctrlUrl) { 
			try {
				this.ctrlUrl = ctrlUrl == null ? null : ctrlUrl.trim();
				checkCtrlUrlValue(this.ctrlUrl);
			} catch (FortifyException e) {
				LOGGER.log(Level.WARNING, "Fortify ScanCentral Controller URL configuration error: " + e.getMessage());
				this.ctrlUrl = null;
			}
		}

		public String getCtrlToken() { return ctrlToken == null ? "" : ctrlToken.getPlainText(); }

		@DataBoundSetter
		public void setCtrlToken(String ctrlToken) { 
			this.ctrlToken = ctrlToken == null || ctrlToken.trim().isEmpty() ? null : Secret.fromString(ctrlToken.trim());
		}

		public boolean isPreventLocalScans() {
			return preventLocalScans;
		}

		@DataBoundSetter
		public void setPreventLocalScans(boolean preventLocalScans) {
			this.preventLocalScans = preventLocalScans;
		}

		public FormValidation doCheckBreakdownPageSize(@QueryParameter String value) {
			if (StringUtils.isBlank(value)) {
				return FormValidation.ok();
			}

			int pageSize = 0;
			try {
				pageSize = Integer.parseInt(value);
			} catch (NumberFormatException e) {
				return FormValidation.warning("Expected an integer value greater than zero.");
			}
			if (pageSize < 1) {
				return FormValidation.warning("Expected an integer value greater than zero.");
			}
			return FormValidation.ok();
		}

		public FormValidation doCheckUrl(@QueryParameter String value) {
			try {
				checkUrlValue(value == null ? null : value.trim());
			} catch (FortifyException e) {
				return FormValidation.warning(e.getMessage());
			}
			return FormValidation.ok();
		}

		@POST
		public FormValidation doCheckToken(@QueryParameter String value, @QueryParameter String url) {
			if (StringUtils.isBlank(value) && (StringUtils.isNotBlank(url) && doCheckUrl(url) == FormValidation.ok())) {
				return FormValidation.warning("Authentication token cannot be empty");
			}
			return FormValidation.ok();
		}

		@POST
		public FormValidation doCheckCtrlToken(@QueryParameter String value, @QueryParameter String ctrlUrl) {
			if (StringUtils.isBlank(value) && StringUtils.isNotBlank(ctrlUrl)) {
				return FormValidation.warning("Controller token cannot be empty");
			}
			return FormValidation.ok();
		}

		// don't think this is used
		public FormValidation doCheckFpr(@QueryParameter String value) {
			if (StringUtils.isBlank(value) || value.charAt(0) == '$') { // parameterized values are not checkable
				return FormValidation.ok();
			} else if (value.contains("/") || value.contains("\\")
					|| !FilenameUtils.isExtension(value.toLowerCase(), new String[] { "fpr", "zip" })) {
				return FormValidation.error("The filename should be in basename *ONLY*, with extension FPR or ZIP");
			} else {
				return FormValidation.ok();
			}
		}

		public FormValidation doCheckProjectTemplate(@QueryParameter String value) {
			try {
				checkProjectTemplateName(value.trim());
			} catch (FortifyException e) {
				return FormValidation.error(e.getMessage());
			}
			return FormValidation.ok();
		}

		public FormValidation doCheckProjectName(@QueryParameter String value) {
			return FormValidation.ok();
		}

		public FormValidation doCheckProjectVersion(@QueryParameter String value) {
			return FormValidation.ok();
		}

		public FormValidation doCheckTimeout(@QueryParameter String value) {
			if (StringUtils.isBlank(value)) {
				return FormValidation.ok();
			} else {
				try {
					int x = Integer.parseInt(value);
					if (x >= 0 && x <= 10080) {
						return FormValidation.ok();
					} else {
						return FormValidation.error("Timeout must be in the range of 0 to 10080");
					}
				} catch (NumberFormatException e) {
					return FormValidation.error("Timeout is invalid");
				}
			}
		}

		public FormValidation doCheckPollingInterval(@QueryParameter String value) {
			if (StringUtils.isBlank(value) || value.charAt(0) == '$') {
				return FormValidation.ok();
			} else {
				int x = -1;
				try {
					x = Integer.parseInt(value);
					if (x >= 0 && x <= 60)
						return FormValidation.ok();
				} catch (NumberFormatException e) {
				}
				return FormValidation.error("The unit is in minutes, and in the range of 0 to 60");
			}
		}

		public FormValidation doCheckCtrlUrl(@QueryParameter String value, @QueryParameter String url) {
			if (StringUtils.isBlank(value)) {
				if (StringUtils.isNotBlank(url) && (doCheckUrl(url) == FormValidation.ok())) {
					return FormValidation.okWithMarkup("<font color=\"blue\">Will use the SSC URL to determine the Controller location</font>");
				} else {
					return FormValidation.error("Controller URL and SSC URL cannot both be empty");
				}
			}
			try {
				checkCtrlUrlValue(value == null ? null : value.trim());
			} catch (FortifyException e) {
				return FormValidation.warning(e.getMessage());
			}
			return FormValidation.ok();
		}

		public FormValidation doTestConnection(@QueryParameter String url, @QueryParameter String token, 
				@QueryParameter boolean proxyConfig, @QueryParameter String proxyUrl, @QueryParameter String proxyUsername, @QueryParameter String proxyPassword) {
			String sscUrl = url == null ? "" : url.trim();
			try {
				checkUrlValue(sscUrl);
			} catch (FortifyException e) {
				return FormValidation.error(e.getMessage());
			}
			String userToken = token == null ? "" : token.trim();
			if (StringUtils.isEmpty(userToken)) {
				return FormValidation.error("Authentication token cannot be empty");
			} else if (userToken.indexOf(' ') != -1) {
				return FormValidation.error("Authentication token should not contain spaces");
			}

			// backup original values
			String orig_url = this.url;
			Secret orig_token = this.token;
			ProxyConfig orig_proxy = this.proxyConfig;
			this.url = sscUrl;
			this.token = userToken.isEmpty() ? null : Secret.fromString(userToken);
			this.proxyConfig = !proxyConfig ? null : new ProxyConfig(proxyUrl == null ? "" : proxyUrl.trim(), proxyUsername == null ? null : Secret.fromString(proxyUsername.trim()), proxyPassword == null ? null : Secret.fromString(proxyPassword.trim()));
			try {
				runWithFortifyClient(userToken, new FortifyClient.Command<FortifyClient.NoReturn>() {
					@Override
					public NoReturn runWith(FortifyClient client) throws Exception {
						// as long as no exception, that's ok
						client.getProjectList(1);
						return FortifyClient.NoReturn.INSTANCE;
					}
				});
				return FormValidation.okWithMarkup("<font color=\"blue\">Connection successful!</font>");
			} catch (Throwable t) {
				if (t.getMessage().contains("Access Denied")) {
					return FormValidation.error(t, "Invalid token");
				}
				return FormValidation.error(t, "Cannot connect to SSC server");
			} finally {
				this.url = orig_url;
				this.token = orig_token;
				this.proxyConfig = orig_proxy;
			}
		}

		public FormValidation doTestCtrlConnection(@QueryParameter String ctrlUrl) throws IOException {
			String controllerUrl = ctrlUrl == null ? "" : ctrlUrl.trim();
			try {
				checkCtrlUrlValue(controllerUrl);
			} catch (FortifyException e) {
				return FormValidation.error(e.getMessage());
			}

			// backup original values
			String orig_url = this.ctrlUrl;

			this.ctrlUrl = controllerUrl;
			OkHttpClient client = new OkHttpClient();

			Request request = new Request.Builder().url(controllerUrl).build();
			Response response = null;
			try {
				response = client.newCall(request).execute();
				if (response.isSuccessful() && (response.body().string().contains("Fortify ScanCentral Controller") ||
						response.body().string().contains("Fortify CloudScan Controller"))) {
					return FormValidation.okWithMarkup("<font color=\"blue\">Connection successful!</font>");
				} else {
					return FormValidation.error("Connection failed. Check the Controller URL.");
				}
			} catch (Throwable t) {
				return FormValidation.error(t, "Cannot connect to Controller");
			} finally {
				this.ctrlUrl = orig_url;

				if (response != null && response.body() != null) {
					response.body().close();
				}
			}
		}

		private void checkUrlValue(String sscUrl) throws FortifyException {
			if (StringUtils.isNotBlank(sscUrl)) {
				if (StringUtils.startsWith(sscUrl, "http://") || StringUtils.startsWith(sscUrl, "https://")) {
					if (sscUrl.trim().equalsIgnoreCase("http://") || sscUrl.trim().equalsIgnoreCase("https://")) {
						throw new FortifyException(new Message(Message.ERROR, "URL host is required"));
					}
				} else {
					throw new FortifyException(new Message(Message.ERROR, "Invalid protocol"));
				}
				if (sscUrl.indexOf(' ') != -1) {
					throw new FortifyException(new Message(Message.ERROR, "URL cannot have spaces"));
				}
			}
		}

		private void checkProjectTemplateName(String projectTemplateName) throws FortifyException {
			if (StringUtils.isNotBlank(projectTemplateName)) {
				boolean valid = false;
				List<ProjectTemplateBean> projectTemplateList = getProjTemplateListList();
				if (projectTemplateList != null) {
					for (ProjectTemplateBean projectTemplateBean : projectTemplateList) {
						if (projectTemplateBean.getName().equals(projectTemplateName)) {
							valid = true;
						}
					}
					if (!valid) {
						throw new FortifyException(new Message(Message.ERROR, "Invalid Issue Template \"" + projectTemplateName + "\"."));
					}
				}
			}
		}

		private void checkCtrlUrlValue(String ctrlUrl) throws FortifyException {
			if (StringUtils.isNotBlank(ctrlUrl)) {
				if (StringUtils.startsWith(ctrlUrl, "http://") || StringUtils.startsWith(ctrlUrl, "https://")) {
					if (ctrlUrl.trim().equalsIgnoreCase("http://") || ctrlUrl.trim().equalsIgnoreCase("https://")) {
						throw new FortifyException(new Message(Message.ERROR, "URL host is required"));
					}
					if (ctrlUrl.endsWith("/")) {
						ctrlUrl = ctrlUrl.substring(0, ctrlUrl.length()-1);
					}
					if (!StringUtils.endsWith(ctrlUrl,"/scancentral-ctrl") && !StringUtils.endsWith(ctrlUrl,"/cloud-ctrl")) {
						throw new FortifyException(new Message(Message.ERROR, "Invalid context"));
					}
				} else {
					throw new FortifyException(new Message(Message.ERROR, "Invalid protocol"));
				}
				if (ctrlUrl.indexOf(' ') != -1) {
					throw new FortifyException(new Message(Message.ERROR, "URL cannot have spaces"));
				}
			}
		}

		public void doRefreshProjects(StaplerRequest req, StaplerResponse rsp, @QueryParameter String value)
				throws Exception {
			try {
				// always retrieve data from SSC
				allProjects = getAllProjectsNoCache();
				// and then convert it to JSON
				StringBuilder buf = new StringBuilder();
				List<String> projects = new ArrayList<String>(allProjects.keySet());
				Collections.sort(projects, String.CASE_INSENSITIVE_ORDER);
				for (String prjName : projects) {
					if (buf.length() > 0) {
						buf.append(",");
					}
					buf.append("{ \"name\": \"" + escapeJsonValue(prjName) + "\" }\n");
				}
				buf.insert(0, "{ \"list\" : [\n");
				buf.append("]}");
				// send HTML data directly
				rsp.setContentType("text/html;charset=UTF-8");
				rsp.getWriter().print(buf.toString());
			} catch (Exception e) {
				e.printStackTrace();
				throw e;
			}
		}

		public void doRefreshVersions(StaplerRequest req, StaplerResponse rsp, @QueryParameter String value)
				throws Exception {
			try {
				// always retrieve data from SSC
				allProjects = getAllProjects();
				// and then convert it to JSON
				StringBuilder buf = new StringBuilder();
				for (Map.Entry<String, Map<String, Long>> prj : allProjects.entrySet()) {
					List<String> versions = new ArrayList<String>(prj.getValue().keySet());
					Collections.sort(versions, String.CASE_INSENSITIVE_ORDER);
					for (String prjVersion : versions) {
						if (buf.length() > 0) {
							buf.append(",");
						}
						buf.append("{ \"name\": \"" + escapeJsonValue(prjVersion) + "\", \"prj\": \"" + escapeJsonValue(prj.getKey()) + "\" }\n");
					}
				}
				buf.insert(0, "{ \"list\" : [\n");
				buf.append("]}");
				// send HTML data directly
				rsp.setContentType("text/html;charset=UTF-8");
				rsp.getWriter().print(buf.toString());
			} catch (Exception e) {
				e.printStackTrace();
				throw e;
			}
		}

		private String escapeJsonValue(String stringValue) {
			return stringValue.replace("\"", "\\\"");
		}

		public void doRefreshProjectTemplates(StaplerRequest req, StaplerResponse rsp, @QueryParameter String value) throws Exception {
			// backup original values
			String orig_url = this.url;
			ProxyConfig orig_proxy = proxyConfig;
			Secret orig_token = this.token;

			String url = req.getParameter("url");
			String proxyEnabled = req.getParameter("proxyConfig");
			if (proxyEnabled == null) {
				proxyEnabled = req.getParameter("_.proxyConfig");
			}
			boolean useProxyParam = "true".equalsIgnoreCase(proxyEnabled);
			String proxyUrl = req.getParameter("proxyUrl");
			String proxyUsername = req.getParameter("proxyUsername");
			String proxyPassword = req.getParameter("proxyPassword");
			this.proxyConfig = useProxyParam ? new ProxyConfig(proxyUrl == null ? "" : proxyUrl.trim(), proxyUsername == null ? null : Secret.fromString(proxyUsername.trim()), proxyPassword == null ? null : Secret.fromString(proxyPassword.trim())) : null;
			String token = req.getParameter("token");
			this.url = url != null ? url.trim() : "";
			this.token = token != null ? Secret.fromString(token.trim()) : null;

			try {
				FormValidation testConnectionResult = doTestConnection(this.url, this.getToken(), useProxyParam, proxyUrl, proxyUsername, proxyPassword);
				if (!testConnectionResult.kind.equals(FormValidation.Kind.OK)) {
					LOGGER.log(Level.WARNING, "Can't retrieve Fortify Issue Template list because of SSC server connection problem: " + testConnectionResult.getLocalizedMessage());
					return; // don't get templates if server is unavailable
				}
				// always retrieve data from SSC
				projTemplateList = getProjTemplateListNoCache();
				// and then convert it to JSON
				StringBuilder buf = new StringBuilder();
				buf.append("{ \"list\" : [\n");
				for (int i = 0; i < projTemplateList.size(); i++) {
					ProjectTemplateBean b = projTemplateList.get(i);
					buf.append("{ \"name\": \"" + escapeJsonValue(b.getName()) + "\", \"id\": \"" + escapeJsonValue(b.getId()) + "\" }");
					if (i != projTemplateList.size() - 1) {
						buf.append(",\n");
					} else {
						buf.append("\n");
					}
				}
				buf.append("]}");
				// send HTML data directly
				rsp.setContentType("text/html;charset=UTF-8");
				rsp.getWriter().print(buf.toString());
			} catch (Exception e) {
				e.printStackTrace();
				throw e;
			} finally {
				this.url = orig_url;
				this.proxyConfig = orig_proxy;
				this.token = orig_token;
			}
		}

		public void doRefreshSensorPools(StaplerRequest req, StaplerResponse rsp, @QueryParameter String value) throws Exception {
			// backup original values
			String orig_url = this.url;
			ProxyConfig orig_proxyConfig = this.proxyConfig;
			Secret orig_token = this.token;

			String url = req.getParameter("url");
			this.url = url != null ? url.trim() : "";
			String proxyEnabled = req.getParameter("proxyConfig");
			if (proxyEnabled == null) {
				proxyEnabled = req.getParameter("_.proxyConfig");
			}
			boolean useProxyParam = "true".equalsIgnoreCase(proxyEnabled);
			String proxyUrl = req.getParameter("proxyUrl");
			String proxyUsername = req.getParameter("proxyUsername");
			String proxyPassword = req.getParameter("proxyPassword");
			proxyConfig = useProxyParam ? new ProxyConfig(proxyUrl == null ? "" : proxyUrl.trim(), proxyUsername == null ? null : Secret.fromString(proxyUsername.trim()), proxyPassword == null ? null : Secret.fromString(proxyPassword.trim())) : null;
			String token = req.getParameter("token");
			this.token = token != null ? Secret.fromString(token.trim()) : null;

			try {
				FormValidation testConnectionResult = doTestConnection(this.url, this.getToken(), useProxyParam, proxyUrl, proxyUsername, proxyPassword);
				if (!testConnectionResult.kind.equals(FormValidation.Kind.OK)) {
					throw new Exception(testConnectionResult.getLocalizedMessage()); // don't get sensor pools if server is unavailable
				}
				// always retrieve data from SSC
				sensorPoolList = getSensorPoolListNoCache();
				// and then convert it to JSON
				StringBuilder buf = new StringBuilder();
				buf.append("{ \"list\" : [\n");
				for (int i = 0; i < sensorPoolList.size(); i++) {
					SensorPoolBean b = sensorPoolList.get(i);
					buf.append("{ \"name\": \"" + escapeJsonValue(b.getName()) + "\", \"uuid\": \"" + escapeJsonValue(b.getUuid()) + "\" }");
					if (i != sensorPoolList.size() - 1) {
						buf.append(",\n");
					} else {
						buf.append("\n");
					}
				}
				buf.append("]}");
				// send HTML data directly
				rsp.setContentType("text/html;charset=UTF-8");
				rsp.getWriter().print(buf.toString());
			} catch (Exception e) {
				e.printStackTrace();
				throw e;
			} finally {
				this.url = orig_url;
				this.proxyConfig = orig_proxyConfig;
				this.token = orig_token;
			}
		}

		public void doCreateNewProject(final StaplerRequest req, StaplerResponse rsp, @QueryParameter String value) throws Exception {
			try {
				runWithFortifyClient(getToken(), new FortifyClient.Command<FortifyClient.NoReturn>() {
					@Override
					public NoReturn runWith(FortifyClient client) throws Exception {
						Writer w = new OutputStreamWriter(System.out, "UTF-8");
						client.createProject(req.getParameter("newprojName"), req.getParameter("newprojVersion"),
								req.getParameter("newprojTemplate"), Collections.<String, String>emptyMap(),
								new PrintWriter(w));
						return FortifyClient.NoReturn.INSTANCE;
					}
				});
			} catch (Exception e) {
				e.printStackTrace();
			}

			doRefreshProjects(req, rsp, value);
		}

		private boolean isSettingUpdated = false;

		@Override
		public boolean configure(StaplerRequest req, JSONObject jsonObject) throws FormException {
			// reset optional proxy configuration to default before data-binding
			BulkChange b = new BulkChange(this);
			try {
				// reset optional authentication to default before data-binding
				// Would not be necessary by https://github.com/jenkinsci/jenkins/pull/3669
				proxyConfig = null;
				url = null;
				token = null;
				projectTemplate = null;
				breakdownPageSize = DEFAULT_PAGE_SIZE;
				dropdownLimit = DEFAULT_DROPDOWN_LIMIT;
				readTimeout = null;
				writeTimeout = null;
				connectTimeout = null;
				ctrlUrl = null;
				ctrlToken = null;
				preventLocalScans = false;
				req.bindJSON(this, jsonObject);
				b.commit();
			} catch (JSONException e) {
				b.abort();
				throw new FormException("Cannot restore configuration property. Will use default (empty) values.", e, null);
			} catch (IOException e) {
				b.abort();
				throw new FormException("Failed to apply configuration", e, null);
			} finally {
				b.close();
			}
			save();
			isSettingUpdated = true;
			return super.configure(req, jsonObject);
		}

		public boolean isSettingUpdated() {
			try {
				return isSettingUpdated;
			} finally {
				isSettingUpdated = false;
			}
		}

		public ComboBoxModel doFillAppNameItems() {
			Map<String, Map<String, Long>> allPrj = getAllProjects();
			return new ComboBoxModel(allPrj.keySet());
		}

		public ComboBoxModel getAppNameItems() {
			return doFillAppNameItems();
		}

		public ComboBoxModel doFillAppVersionItems(@QueryParameter String appName) {
			Map<String, Long> allPrjVersions = getAllProjects().get(appName);
			if (null == allPrjVersions) {
				return new ComboBoxModel(Collections.<String>emptyList());
			}
			return new ComboBoxModel(allPrjVersions.keySet());
		}

		public ComboBoxModel getAppVersionItems(@QueryParameter String appName) {
			return doFillAppVersionItems(appName);
		}

		private Map<String, Map<String, Long>> getAllProjects() {
			if (allProjects.isEmpty()) {
				allProjects = getAllProjectsNoCache();
			}
			return allProjects;
		}

		private Map<String, Map<String, Long>> getAllProjectsNoCache() {
			if (canUploadToSsc()) {
				try {
					Map<String, Map<String, Long>> map = runWithFortifyClient(getToken(),
							new FortifyClient.Command<Map<String, Map<String, Long>>>() {
								@Override
								public Map<String, Map<String, Long>> runWith(FortifyClient client) throws Exception {
									return client.getProjectListEx(getDropdownLimit());
								}
							});
					return map;
					// many strange thing can happen.... need to catch throwable
				} catch (Throwable e) {
					e.printStackTrace();
					return Collections.emptyMap();
				}
			} else {
				return Collections.emptyMap();
			}
		}

		/**
		 * Get Issue template list from SSC via WS <br/>
		 * Basically only for global.jelly pull down menu
		 *
		 * @return A list of Issue template and ID
		 * @throws ApiException
		 */
		public ComboBoxModel doFillProjectTemplateItems() {
			if (projTemplateList.isEmpty()) {
				projTemplateList = getProjTemplateListNoCache();
			}

			List<String> names = new ArrayList<String>(projTemplateList.size());
			for (ProjectTemplateBean b : projTemplateList) {
				names.add(b.getName());
			}
			return new ComboBoxModel(names);
		}

		public ComboBoxModel getProjectTemplateItems() {
			return doFillProjectTemplateItems();
		}

		public List<ProjectTemplateBean> getProjTemplateListList() {
			if (projTemplateList.isEmpty()) {
				projTemplateList = getProjTemplateListNoCache();
			}
			return projTemplateList;
		}

		private List<ProjectTemplateBean> getProjTemplateListNoCache() {
			if (canUploadToSsc()) {
				try {
					Map<String, String> map = runWithFortifyClient(getToken(),
							new FortifyClient.Command<Map<String, String>>() {
								@Override
								public Map<String, String> runWith(FortifyClient client) throws Exception {
									return client.getProjectTemplateList();
								}
							});
					List<ProjectTemplateBean> list = new ArrayList<ProjectTemplateBean>(map.size());
					for (Map.Entry<String, String> entry : map.entrySet()) {
						ProjectTemplateBean proj = new ProjectTemplateBean(entry.getKey(), entry.getValue());
						list.add(proj);
					}
					Collections.sort(list);
					return list;
				} catch (Throwable e) {
					e.printStackTrace();
				}
			}
			return Collections.emptyList();
		}

		public ListBoxModel doFillSensorPoolUUIDItems() {
			sensorPoolList = getSensorPoolListNoCache();

			List<ListBoxModel.Option> optionList = new ArrayList<>();

			for (SensorPoolBean sensorPoolBean : sensorPoolList) {
				ListBoxModel.Option option = new ListBoxModel.Option(sensorPoolBean.getName(), sensorPoolBean.getUuid());
				optionList.add(option);
			}

			return new ListBoxModel(optionList);
		}

		public List<SensorPoolBean> getSensorPoolList() {
			if (sensorPoolList.isEmpty()) {
				sensorPoolList = getSensorPoolListNoCache();
			}
			return sensorPoolList;
		}

		private List<SensorPoolBean> getSensorPoolListNoCache() {
			if (DESCRIPTOR.getUrl() == null) {
				return Collections.emptyList();
			}
			try {
				Map<String, String> map = runWithFortifyClient(getToken(),
						new FortifyClient.Command<Map<String, String>>() {
							@Override
							public Map<String, String> runWith(FortifyClient client) throws Exception {
								return client.getCloudScanPoolList();
							}
						});
				List<SensorPoolBean> list = new ArrayList<SensorPoolBean>(map.size());
				for (Map.Entry<String, String> entry : map.entrySet()) {
					SensorPoolBean proj = new SensorPoolBean(entry.getKey(), entry.getValue());
					list.add(proj);
				}
				Collections.sort(list);
				return list;
			} catch (Throwable e) {
				e.printStackTrace();
			}

			return Collections.emptyList();
		}

		public ListBoxModel doFillLocaleItems(String value) {
			ListBoxModel items = new ListBoxModel();
			items.add("English", "en");
			items.add("Chinese Simplified", "zh_CN");
			items.add("Chinese Traditional", "zh_TW");
			items.add("Japanese", "ja");
			items.add("Korean", "ko");
			items.add("Portuguese (Brazil)", "pt_BR");
			items.add("Spanish", "es");

			if ((null == value) || (0 == value.length())) {
				items.get(0).selected = true; // default to en_US
			}

			return items;
		}
	}

	public static class UploadSSCBlock {
		private transient String projectName;
		private transient String projectVersion;
		private String appName;
		private String appVersion;
		private String filterSet;
		private String searchCondition;
		private Integer timeout;
		private Integer pollingInterval;

		@DataBoundConstructor
		public UploadSSCBlock(String appName, String appVersion) {
			this.appName = appName != null ? appName.trim() : "";
			this.appVersion = appVersion != null ? appVersion.trim() : "";
		}

		@Deprecated
		public UploadSSCBlock(String projectName, String projectVersion, String filterSet, String searchCondition,
							  Integer timeout, Integer pollingInterval) {
			this.projectName = projectName != null ? projectName.trim() : "";
			this.projectVersion = projectName != null ? projectVersion.trim() : "";
			this.filterSet = filterSet != null ? filterSet.trim() : "";
			this.searchCondition = searchCondition != null ? searchCondition.trim() : "";
			this.timeout = timeout;
			this.pollingInterval = pollingInterval;
		}

		/* for backwards compatibility */
		protected Object readResolve() {
			if (projectName != null) {
				appName = projectName;
			}
			if (projectVersion != null) {
				appVersion = projectVersion;
			}
			return this;
		}

		@Deprecated
		public String getProjectName() {
			return projectName;
		}

		@Deprecated
		public String getProjectVersion() {
			return projectVersion;
		}

		public String getAppName() {
			return appName;
		}

		public String getAppVersion() {
			return appVersion;
		}

		public String getFilterSet() {
			return filterSet;
		}
		@DataBoundSetter
		public void setFilterSet(String filterSet) { this.filterSet = filterSet; }

		public String getSearchCondition() {
			return searchCondition;
		}
		@DataBoundSetter
		public void setSearchCondition(String searchCondition) { this.searchCondition = searchCondition; }

		public Integer getTimeout() {
			return timeout;
		}

		@DataBoundSetter
		public void setTimeout(Integer timeout) {
			this.timeout = timeout;
		}

		public Integer getPollingInterval() {
			return pollingInterval;
		}

		@DataBoundSetter
		public void setPollingInterval(Integer pollingInterval) {
			this.pollingInterval = pollingInterval;
		}
	}

	@Deprecated
	public static class RunTranslationBlock {
		private TranslationTypeBlock translationType;
		private boolean debug;
		private boolean verbose;
		private String logFile;

		@DataBoundConstructor
		public RunTranslationBlock(TranslationTypeBlock translationType, boolean translationDebug,
				boolean translationVerbose, String translationLogFile) {
			this.translationType = translationType;
			this.debug = translationDebug;
			this.verbose = translationVerbose;
			this.logFile = translationLogFile != null ? translationLogFile.trim() : "";
		}

		public boolean isBasicTranslationType() {
			return translationType instanceof BasicTranslationBlock;
		}

		public boolean isAdvancedTranslationType() {
			return translationType instanceof AdvancedTranslationBlock;
		}

		public boolean isBasicJavaTranslationType() {
			return isBasicTranslationType() && ((BasicTranslationBlock) translationType)
					.getTranslationApplicationTypeBlock() instanceof BasicJavaTranslationAppTypeBlock;
		}

		public boolean isBasicDotNetTranslationType() {
			return isBasicTranslationType() && ((BasicTranslationBlock) translationType)
					.getTranslationApplicationTypeBlock() instanceof BasicDotNetTranslationAppTypeBlock;
		}

		public boolean isBasicMaven3TranslationType() {
			return isBasicTranslationType() && ((BasicTranslationBlock) translationType)
					.getTranslationApplicationTypeBlock() instanceof BasicMaven3TranslationAppTypeBlock;
		}

		public boolean isBasicGradleTranslationType() {
			return isBasicTranslationType() && ((BasicTranslationBlock) translationType)
					.getTranslationApplicationTypeBlock() instanceof BasicGradleTranslationAppTypeBlock;
		}

		public boolean isBasicOtherTranslationType() {
			return isBasicTranslationType() && ((BasicTranslationBlock) translationType)
					.getTranslationApplicationTypeBlock() instanceof BasicOtherTranslationAppTypeBlock;
		}

		public boolean isBasicDotNetProjectSolutionScanType() {
			return isBasicDotNetTranslationType()
					&& getBasicDotNetTranslationAppTypeBlock().isProjectSolutionScanType();
		}

		public boolean isBasicDotNetSourceCodeScanType() {
			return isBasicDotNetTranslationType() && getBasicDotNetTranslationAppTypeBlock().isSourceCodeScanType();
		}

		public boolean isBasicDotNetDevenvBuildType() {
			return isBasicDotNetProjectSolutionScanType()
					&& getBasicDotNetTranslationAppTypeBlock().isDevenvBuildType();
		}

		public boolean isBasicDotNetMSBuildBuildType() {
			return isBasicDotNetProjectSolutionScanType()
					&& getBasicDotNetTranslationAppTypeBlock().isMSBuildBuildType();
		}

		private BasicJavaTranslationAppTypeBlock getBasicJavaTranslationAppTypeBlock() {
			return isBasicJavaTranslationType()
					? (BasicJavaTranslationAppTypeBlock) ((BasicTranslationBlock) translationType)
							.getTranslationApplicationTypeBlock()
					: null;
		}

		private BasicDotNetTranslationAppTypeBlock getBasicDotNetTranslationAppTypeBlock() {
			return isBasicDotNetTranslationType()
					? (BasicDotNetTranslationAppTypeBlock) ((BasicTranslationBlock) translationType)
							.getTranslationApplicationTypeBlock()
					: null;
		}

		private BasicMaven3TranslationAppTypeBlock getBasicMaven3TranslationAppTypeBlock() {
			return isBasicMaven3TranslationType()
					? (BasicMaven3TranslationAppTypeBlock) ((BasicTranslationBlock) translationType)
							.getTranslationApplicationTypeBlock()
					: null;
		}

		private BasicGradleTranslationAppTypeBlock getBasicGradleTranslationAppTypeBlock() {
			return isBasicGradleTranslationType()
					? (BasicGradleTranslationAppTypeBlock) ((BasicTranslationBlock) translationType)
							.getTranslationApplicationTypeBlock()
					: null;
		}

		private BasicOtherTranslationAppTypeBlock getBasicOtherTranslationAppTypeBlock() {
			return isBasicOtherTranslationType()
					? (BasicOtherTranslationAppTypeBlock) ((BasicTranslationBlock) translationType)
							.getTranslationApplicationTypeBlock()
					: null;
		}

		public String getTranslationType() {
			return isBasicTranslationType() ? "translationBasic" : "translationAdvanced";
		}

		public String getTranslationOptions() {
			return isAdvancedTranslationType() ? ((AdvancedTranslationBlock) translationType).getTranslationOptions()
					: "";
		}

		public String getTranslationExcludeList() {
			return isBasicTranslationType() ? ((BasicTranslationBlock) translationType).getTranslationExcludeList()
					: "";
		}

		public String getTranslationJavaVersion() {
			return isBasicJavaTranslationType() ? getBasicJavaTranslationAppTypeBlock().getTranslationJavaVersion()
					: "";
		}

		public String getTranslationClasspath() {
			return isBasicJavaTranslationType() ? getBasicJavaTranslationAppTypeBlock().getTranslationClasspath() : "";
		}

		public String getTranslationSourceFiles() {
			return isBasicJavaTranslationType() ? getBasicJavaTranslationAppTypeBlock().getTranslationSourceFiles()
					: "";
		}

		public String getTranslationAddOptions() {
			return isBasicJavaTranslationType() ? getBasicJavaTranslationAppTypeBlock().getTranslationAddOptions() : "";
		}

		public String getDotNetDevenvProjects() {
			return isBasicDotNetTranslationType() ? getBasicDotNetTranslationAppTypeBlock().getDevenvProjects() : "";
		}

		public String getDotNetDevenvAddOptions() {
			return isBasicDotNetTranslationType() ? getBasicDotNetTranslationAppTypeBlock().getDevenvAddOptions() : "";
		}

		public String getDotNetMSBuildProjects() {
			return isBasicDotNetTranslationType() ? getBasicDotNetTranslationAppTypeBlock().getMSBuildProjects() : "";
		}

		public String getDotNetMSBuildAddOptions() {
			return isBasicDotNetTranslationType() ? getBasicDotNetTranslationAppTypeBlock().getMSBuildAddOptions() : "";
		}

		public String getDotNetSourceCodeFrameworkVersion() {
			return isBasicDotNetTranslationType()
					? getBasicDotNetTranslationAppTypeBlock().getSourceCodeFrameworkVersion()
					: "";
		}

		public String getDotNetSourceCodeLibdirs() {
			return isBasicDotNetTranslationType() ? getBasicDotNetTranslationAppTypeBlock().getSourceCodeLibdirs() : "";
		}

		public String getDotNetSourceCodeAddOptions() {
			return isBasicDotNetTranslationType() ? getBasicDotNetTranslationAppTypeBlock().getSourceCodeAddOptions()
					: "";
		}

		public String getDotNetSourceCodeSrcFiles() {
			return isBasicDotNetTranslationType() ? getBasicDotNetTranslationAppTypeBlock().getSourceCodeSrcFiles()
					: "";
		}

		public String getMaven3Options() {
			return isBasicMaven3TranslationType() ? getBasicMaven3TranslationAppTypeBlock().getOptions() : "";
		}

		public boolean getGradleUseWrapper() {
			return isBasicGradleTranslationType() && getBasicGradleTranslationAppTypeBlock().getUseWrapper();
		}

		public String getGradleTasks() {
			return isBasicGradleTranslationType() ? getBasicGradleTranslationAppTypeBlock().getTasks() : "";
		}

		public String getGradleOptions() {
			return isBasicGradleTranslationType() ? getBasicGradleTranslationAppTypeBlock().getOptions() : "";
		}

		public String getOtherOptions() {
			return isBasicOtherTranslationType() ? getBasicOtherTranslationAppTypeBlock().getOptions() : "";
		}

		public String getOtherIncludesList() {
			return isBasicOtherTranslationType() ? getBasicOtherTranslationAppTypeBlock().getIncludesList() : "";
		}

		public boolean getTranslationDebug() {
			return debug;
		}

		public boolean getTranslationVerbose() {
			return verbose;
		}

		public String getTranslationLogFile() {
			return logFile;
		}
	}

	@Deprecated
	public interface TranslationTypeBlock {
	}

	@Deprecated
	public static class BasicTranslationBlock implements TranslationTypeBlock {
		private BasicTranslationAppTypeBlock appTypeBlock;
		private String excludeList;

		@DataBoundConstructor
		public BasicTranslationBlock(BasicTranslationAppTypeBlock translationAppType, String translationExcludeList) {
			this.appTypeBlock = translationAppType;
			this.excludeList = translationExcludeList != null ? translationExcludeList.trim() : "";
		}

		public BasicTranslationAppTypeBlock getTranslationApplicationTypeBlock() {
			return appTypeBlock;
		}

		public String getTranslationExcludeList() {
			return excludeList;
		}
	}

	@Deprecated
	public static class AdvancedTranslationBlock implements TranslationTypeBlock {
		private String translationOptions;

		@DataBoundConstructor
		public AdvancedTranslationBlock(String translationOptions) {
			this.translationOptions = translationOptions != null ? translationOptions.trim() : "";
		}

		public String getTranslationOptions() {
			return translationOptions;
		}
	}

	@Deprecated
	public interface BasicTranslationAppTypeBlock {
	}

	@Deprecated
	public static class BasicJavaTranslationAppTypeBlock implements BasicTranslationAppTypeBlock {
		private String javaVersion;
		private String classpath;
		private String sourceFiles;
		private String additionalOptions;

		@DataBoundConstructor
		public BasicJavaTranslationAppTypeBlock(String translationJavaVersion, String translationJavaClasspath,
				String translationJavaSourceFiles, String translationJavaAddOptions) {
			this.javaVersion = translationJavaVersion != null ? translationJavaVersion.trim() : "";
			this.classpath = translationJavaClasspath != null ? translationJavaClasspath.trim() : "";
			this.sourceFiles = translationJavaSourceFiles != null ? translationJavaSourceFiles.trim() : "";
			this.additionalOptions = translationJavaAddOptions != null ? translationJavaAddOptions.trim() : "";
		}

		public String getTranslationJavaVersion() {
			return javaVersion;
		}

		public String getTranslationClasspath() {
			return classpath;
		}

		public String getTranslationSourceFiles() {
			return sourceFiles;
		}

		public String getTranslationAddOptions() {
			return additionalOptions;
		}
	}

	@Deprecated
	public static class BasicDotNetTranslationAppTypeBlock implements BasicTranslationAppTypeBlock {
		private BasicDotNetScanTypeBlock scanType;

		@DataBoundConstructor
		public BasicDotNetTranslationAppTypeBlock(BasicDotNetScanTypeBlock dotNetScanType) {
			scanType = dotNetScanType;
		}

		public boolean isProjectSolutionScanType() {
			return scanType != null && scanType instanceof BasicDotNetProjectSolutionScanTypeBlock;
		}

		public boolean isSourceCodeScanType() {
			return scanType != null && scanType instanceof BasicDotNetSourceCodeScanTypeBlock;
		}

		public BasicDotNetScanTypeBlock getScanTypeBlock() {
			return scanType;
		}

		public boolean isDevenvBuildType() {
			return isProjectSolutionScanType()
					&& ((BasicDotNetProjectSolutionScanTypeBlock) scanType).isDevenvBuildType();
		}

		public boolean isMSBuildBuildType() {
			return isProjectSolutionScanType()
					&& ((BasicDotNetProjectSolutionScanTypeBlock) scanType).isMSBuildBuildType();
		}

		public String getDevenvProjects() {
			return isProjectSolutionScanType()
					? ((BasicDotNetProjectSolutionScanTypeBlock) scanType).getDevenvProjects()
					: "";
		}

		public String getDevenvAddOptions() {
			return isProjectSolutionScanType()
					? ((BasicDotNetProjectSolutionScanTypeBlock) scanType).getDevenvAddOptions()
					: "";
		}

		public String getMSBuildProjects() {
			return isProjectSolutionScanType()
					? ((BasicDotNetProjectSolutionScanTypeBlock) scanType).getMSBuildProjects()
					: "";
		}

		public String getMSBuildAddOptions() {
			return isProjectSolutionScanType()
					? ((BasicDotNetProjectSolutionScanTypeBlock) scanType).getMSBuildAddOptions()
					: "";
		}

		public String getSourceCodeFrameworkVersion() {
			return isSourceCodeScanType() ? ((BasicDotNetSourceCodeScanTypeBlock) scanType).getDotNetVersion() : "";
		}

		public String getSourceCodeLibdirs() {
			return isSourceCodeScanType() ? ((BasicDotNetSourceCodeScanTypeBlock) scanType).getLibdirs() : "";
		}

		public String getSourceCodeAddOptions() {
			return isSourceCodeScanType() ? ((BasicDotNetSourceCodeScanTypeBlock) scanType).getAddOptions() : "";
		}

		public String getSourceCodeSrcFiles() {
			return isSourceCodeScanType() ? ((BasicDotNetSourceCodeScanTypeBlock) scanType).getDotNetSrcFiles() : "";
		}
	}

	@Deprecated
	public interface BasicDotNetScanTypeBlock {
	}

	@Deprecated
	public static class BasicDotNetProjectSolutionScanTypeBlock implements BasicDotNetScanTypeBlock {
		private BasicDotNetBuildTypeBlock buildType;

		@DataBoundConstructor
		public BasicDotNetProjectSolutionScanTypeBlock(BasicDotNetBuildTypeBlock dotNetBuildType) {
			buildType = dotNetBuildType;
		}

		public boolean isDevenvBuildType() {
			return buildType != null && buildType instanceof BasicDotNetDevenvBuildTypeBlock;
		}

		public boolean isMSBuildBuildType() {
			return buildType != null && buildType instanceof BasicDotNetMSBuildBuildTypeBlock;
		}

		public String getDevenvProjects() {
			return isDevenvBuildType() ? ((BasicDotNetDevenvBuildTypeBlock) buildType).getProjects() : "";
		}

		public String getDevenvAddOptions() {
			return isDevenvBuildType() ? ((BasicDotNetDevenvBuildTypeBlock) buildType).getAddOptions() : "";
		}

		public String getMSBuildProjects() {
			return isMSBuildBuildType() ? ((BasicDotNetMSBuildBuildTypeBlock) buildType).getProjects() : "";
		}

		public String getMSBuildAddOptions() {
			return isMSBuildBuildType() ? ((BasicDotNetMSBuildBuildTypeBlock) buildType).getAddOptions() : "";
		}
	}

	@Deprecated
	public static class BasicDotNetSourceCodeScanTypeBlock implements BasicDotNetScanTypeBlock {
		private String dotNetVersion;
		private String libdirs;
		private String addOptions;
		private String dotNetSrcFiles;

		@DataBoundConstructor
		public BasicDotNetSourceCodeScanTypeBlock(String dotNetSourceCodeFrameworkVersion,
				String dotNetSourceCodeLibdirs, String dotNetSourceCodeAddOptions, String dotNetSourceCodeSrcFiles) {
			dotNetVersion = dotNetSourceCodeFrameworkVersion;
			libdirs = dotNetSourceCodeLibdirs;
			addOptions = dotNetSourceCodeAddOptions;
			dotNetSrcFiles = dotNetSourceCodeSrcFiles;
		}

		public String getDotNetVersion() {
			return dotNetVersion;
		}

		public String getLibdirs() {
			return libdirs;
		}

		public String getAddOptions() {
			return addOptions;
		}

		public String getDotNetSrcFiles() {
			return dotNetSrcFiles;
		}
	}

	@Deprecated
	public interface BasicDotNetBuildTypeBlock {
	}

	@Deprecated
	public static class BasicDotNetDevenvBuildTypeBlock implements BasicDotNetBuildTypeBlock {
		private String projects;
		private String addOptions;

		@DataBoundConstructor
		public BasicDotNetDevenvBuildTypeBlock(String dotNetDevenvProjects, String dotNetDevenvAddOptions) {
			projects = dotNetDevenvProjects;
			addOptions = dotNetDevenvAddOptions;
		}

		public String getProjects() {
			return projects;
		}

		public String getAddOptions() {
			return addOptions;
		}
	}

	@Deprecated
	public static class BasicDotNetMSBuildBuildTypeBlock implements BasicDotNetBuildTypeBlock {
		private String projects;
		private String addOptions;

		@DataBoundConstructor
		public BasicDotNetMSBuildBuildTypeBlock(String dotNetMSBuildProjects, String dotNetMSBuildAddOptions) {
			projects = dotNetMSBuildProjects;
			addOptions = dotNetMSBuildAddOptions;
		}

		public String getProjects() {
			return projects;
		}

		public String getAddOptions() {
			return addOptions;
		}
	}

	@Deprecated
	public static class BasicMaven3TranslationAppTypeBlock implements BasicTranslationAppTypeBlock {
		private String options;

		@DataBoundConstructor
		public BasicMaven3TranslationAppTypeBlock(String maven3Options) {
			options = maven3Options;
		}

		public String getOptions() {
			return options;
		}
	}

	@Deprecated
	public static class BasicGradleTranslationAppTypeBlock implements BasicTranslationAppTypeBlock {
		private boolean useWrapper;
		private String tasks;
		private String options;

		@DataBoundConstructor
		public BasicGradleTranslationAppTypeBlock(boolean gradleUseWrapper, String gradleTasks, String gradleOptions) {
			useWrapper = gradleUseWrapper;
			tasks = gradleTasks;
			options = gradleOptions;
		}

		public boolean getUseWrapper() {
			return useWrapper;
		}

		public String getTasks() {
			return tasks;
		}

		public String getOptions() {
			return options;
		}
	}

	@Deprecated
	public static class BasicOtherTranslationAppTypeBlock implements BasicTranslationAppTypeBlock {
		private String options;
		private String includesList;

		@DataBoundConstructor
		public BasicOtherTranslationAppTypeBlock(String otherOptions, String otherIncludesList) {
			options = otherOptions;
			includesList = otherIncludesList;
		}

		public String getOptions() {
			return options;
		}

		public String getIncludesList() {
			return includesList;
		}
	}

	public static class RunScanBlock {
		private String customRulepacks;
		private String additionalOptions;
		private boolean debug;
		private boolean verbose;
		private String logFile;

		@DataBoundConstructor
		public RunScanBlock() {}

		@Deprecated
		public RunScanBlock(String scanCustomRulepacks, String scanAddOptions, boolean scanDebug, boolean scanVerbose,
				String scanLogFile) {
			this.customRulepacks = scanCustomRulepacks != null ? scanCustomRulepacks.trim() : "";
			this.additionalOptions = scanAddOptions != null ? scanAddOptions.trim() : "";
			this.debug = scanDebug;
			this.verbose = scanVerbose;
			this.logFile = scanLogFile != null ? scanLogFile.trim() : "";
		}

		public String getScanCustomRulepacks() {
			return customRulepacks;
		}
		@DataBoundSetter
		public void setScanCustomRulepacks(String scanCustomRulepacks) {
			this.customRulepacks = scanCustomRulepacks;
		}

		public String getScanAddOptions() {
			return additionalOptions;
		}
		@DataBoundSetter
		public void setScanAddOptions(String scanAddOptions) {
			this.additionalOptions = scanAddOptions;
		}

		public boolean getScanDebug() {
			return debug;
		}
		@DataBoundSetter
		public void setScanDebug(boolean scanDebug) { this.debug = scanDebug; }

		public boolean getScanVerbose() {
			return verbose;
		}
		@DataBoundSetter
		public void setScanVerbose(boolean scanVerbose) { this.verbose = scanVerbose; }

		public String getScanLogFile() {
			return logFile;
		}
		@DataBoundSetter
		public void setScanLogFile(String scanLogFile) { this.logFile = scanLogFile; }
	}

	public static class UpdateContentBlock {
		private String updateServerUrl;
		private String locale;
		private UseProxyBlock useProxy;

		@DataBoundConstructor
		public UpdateContentBlock() {}

		@Deprecated
		public UpdateContentBlock(String updateServerUrl, UseProxyBlock updateUseProxy) {
			this.updateServerUrl = updateServerUrl != null ? updateServerUrl.trim() : "";
			this.useProxy = updateUseProxy;
		}

		public String getUpdateServerUrl() {
			return updateServerUrl;
		}
		@DataBoundSetter
		public void setUpdateServerUrl(String updateServerUrl) { this.updateServerUrl = updateServerUrl; }

		public String getLocale() { return locale; }
		@DataBoundSetter
		public void setLocale(String locale) { this.locale = locale; }

		@Deprecated
		public boolean getUpdateUseProxy() {
			return useProxy != null;
		}

		@Deprecated
		public String getUpdateProxyUrl() {
			return useProxy == null ? "" : useProxy.getProxyUrl();
		}

		@Deprecated
		public String getUpdateProxyUsername() {
			return useProxy == null ? "" : useProxy.getProxyUsername();
		}

		@Deprecated
		public String getUpdateProxyPassword() {
			return useProxy == null ? "" : useProxy.getProxyPassword();
		}
	}

	@Deprecated
	public static class UseProxyBlock {
		private String proxyUrl;
		private Secret proxyUsername;
		private Secret proxyPassword;

		@DataBoundConstructor
		public UseProxyBlock(String updateProxyUrl, String updateProxyUsername, String updateProxyPassword) {
			this.proxyUrl = updateProxyUrl != null ? updateProxyUrl.trim() : "";
			this.proxyUsername = updateProxyUsername != null ? Secret.fromString(updateProxyUsername.trim()) : null;
			this.proxyPassword = updateProxyPassword != null ? Secret.fromString(updateProxyPassword.trim()) : null;
		}

		public String getProxyUrl() {
			return proxyUrl;
		}

		public String getProxyUsername() {
			return proxyUsername == null ? "" : proxyUsername.getPlainText();
		}

		public String getProxyPassword() {
			return proxyPassword == null ? "" : proxyPassword.getPlainText();
		}
	}

	public static class AnalysisRunType {
		private String value;
		// remote translation and scan
		private RemoteAnalysisProjectType remoteAnalysisProjectType;
		private RemoteOptionalConfigBlock remoteOptionalConfig;
		private String transArgs;

		// local translation
		private ProjectScanType projectScanType;
		private UpdateContentBlock updateContent;
		private String buildId;
		private String scanFile;
		private Integer maxHeap;
		private String addJVMOptions;
		private String translationExcludeList;
		private boolean translationDebug;
		private boolean translationVerbose;
		private String translationLogFile;

		// local scan
		private RunScanBlock runScan;

		// upload to ssc
		private UploadSSCBlock uploadSSC;

		@DataBoundConstructor
		public AnalysisRunType(String value) {
			this.value = value;
		}

		public RemoteAnalysisProjectType getRemoteAnalysisProjectType() { return remoteAnalysisProjectType; }
		@DataBoundSetter
		public void setRemoteAnalysisProjectType(RemoteAnalysisProjectType remoteAnalysisProjectType) {
			this.remoteAnalysisProjectType = remoteAnalysisProjectType;
		}

		public RemoteOptionalConfigBlock getRemoteOptionalConfig() { return remoteOptionalConfig; }
		@DataBoundSetter
		public void setRemoteOptionalConfig(RemoteOptionalConfigBlock remoteOptionalConfig) { this.remoteOptionalConfig = remoteOptionalConfig; }

		public String getTransArgs() { return transArgs; }
		@DataBoundSetter
		public void setTransArgs(String transArgs) { this.transArgs = transArgs; }

		public ProjectScanType getProjectScanType() {
			return projectScanType;
		}
		@DataBoundSetter
		public void setProjectScanType(ProjectScanType projectScanType) { this.projectScanType = projectScanType; }

		public UpdateContentBlock getUpdateContent() { return updateContent; }
		@DataBoundSetter
		public void setUpdateContent(UpdateContentBlock updateContent) { this.updateContent = updateContent; }

		public String getBuildId() { return buildId; }
		@DataBoundSetter
		public void setBuildId(String buildId) { this.buildId = buildId; }

		public String getScanFile() { return scanFile; }
		@DataBoundSetter
		public void setScanFile(String scanFile) { this.scanFile = scanFile; }

		public Integer getMaxHeap() { return maxHeap; }
		@DataBoundSetter
		public void setMaxHeap(Integer maxHeap) { this.maxHeap = maxHeap; }

		public String getAddJVMOptions() { return addJVMOptions; }
		@DataBoundSetter
		public void setAddJVMOptions(String addJVMOptions) { this.addJVMOptions = addJVMOptions; }

		public String getTranslationExcludeList() { return translationExcludeList; }
		@DataBoundSetter
		public void setTranslationExcludeList(String translationExcludeList) { this.translationExcludeList = translationExcludeList; }

		public boolean isTranslationDebug() { return translationDebug; }
		@DataBoundSetter
		public void setTranslationDebug(boolean translationDebug) { this.translationDebug = translationDebug; }

		public boolean isTranslationVerbose() {
			return translationVerbose;
		}
		@DataBoundSetter
		public void setTranslationVerbose(boolean translationVerbose) { this.translationVerbose = translationVerbose; }

		public String getTranslationLogFile() {
			return translationLogFile;
		}
		@DataBoundSetter
		public void setTranslationLogFile(String translationLogFile) { this.translationLogFile = translationLogFile; }

		public RunScanBlock getRunScan() { return runScan; }
		@DataBoundSetter
		public void setRunScan(RunScanBlock runScan) { this.runScan = runScan; }

		public UploadSSCBlock getUploadSSC() { return uploadSSC; }
		@DataBoundSetter
		public void setUploadSSC(UploadSSCBlock uploadSSC) { this.uploadSSC = uploadSSC; }

	}

	public static class RemoteOptionalConfigBlock {
		private String sensorPoolUUID;
		private String notifyEmail;
		private String scanOptions;
		private String customRulepacks;
		private String filterFile;

		@DataBoundConstructor
		public RemoteOptionalConfigBlock() {}

		public String getSensorPoolUUID() { return sensorPoolUUID; }
		@DataBoundSetter
		public void setSensorPoolUUID(String sensorPoolUUID) { this.sensorPoolUUID = sensorPoolUUID; }

		public String getNotifyEmail() { return notifyEmail; }
		@DataBoundSetter
		public void setNotifyEmail(String notifyEmail) { this.notifyEmail = notifyEmail; }

		public String getScanOptions() { return scanOptions; }
		@DataBoundSetter
		public void setScanOptions(String scanOptions) { this.scanOptions = scanOptions; }

		public String getCustomRulepacks() { return customRulepacks; }
		@DataBoundSetter
		public void setCustomRulepacks(String customRulepacks) { this.customRulepacks = customRulepacks;
		}

		public String getFilterFile() { return filterFile; }
		@DataBoundSetter
		public void setFilterFile(String filterFile) { this.filterFile = filterFile; }

	}

}
