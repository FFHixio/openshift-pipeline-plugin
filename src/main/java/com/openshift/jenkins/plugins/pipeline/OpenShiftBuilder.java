package com.openshift.jenkins.plugins.pipeline;
import hudson.EnvVars;
import hudson.Launcher;
import hudson.Extension;
import hudson.util.FormValidation;
import hudson.model.TaskListener;
import hudson.model.AbstractProject;
import hudson.tasks.Builder;
import hudson.tasks.BuildStepDescriptor;
import net.sf.json.JSONObject;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.QueryParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.openshift.internal.restclient.http.HttpClientException;
import com.openshift.internal.restclient.http.UrlConnectionHttpClient;
import com.openshift.restclient.ClientFactory;
import com.openshift.restclient.IClient;
import com.openshift.restclient.ResourceKind;
import com.openshift.restclient.capability.CapabilityVisitor;
import com.openshift.restclient.capability.resources.IBuildTriggerable;
import com.openshift.restclient.model.IBuild;
import com.openshift.restclient.model.IBuildConfig;
import com.openshift.restclient.model.IPod;

import javax.servlet.ServletException;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.List;


public class OpenShiftBuilder extends OpenShiftBaseStep {
	
    protected String bldCfg = "frontend";
    protected String commitID = "";
    protected String buildName = "";
    protected String showBuildLogs = "false";
    protected String checkForTriggeredDeployments = "false";
    
    
    // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    public OpenShiftBuilder(String apiURL, String bldCfg, String namespace, String authToken, String verbose, String commitID, String buildName, String showBuildLogs, String checkForTriggeredDeployments) {
        this.apiURL = apiURL;
        this.bldCfg = bldCfg;
        this.namespace = namespace;
        this.authToken = authToken;
        this.verbose = verbose;
        this.commitID = commitID;
        this.buildName = buildName;
        this.showBuildLogs = showBuildLogs;
        this.checkForTriggeredDeployments = checkForTriggeredDeployments;
    }

	public String getCommitID() {
		return commitID;
	}

	public String getBuildName() {
		return buildName;
	}

	public String getShowBuildLogs() {
		return showBuildLogs;
	}
	
	public String getBldCfg() {
		return bldCfg;
	}
	
	public String getCheckForTriggeredDeployments() {
		return checkForTriggeredDeployments;
	}
	
	protected boolean coreLogic(Launcher launcher, TaskListener listener, EnvVars env) {
		boolean chatty = Boolean.parseBoolean(verbose);
		boolean checkDeps = Boolean.parseBoolean(checkForTriggeredDeployments);
    	listener.getLogger().println("\n\nBUILD STEP:  OpenShiftBuilder in perform for " + bldCfg + " on namespace " + namespace);
		
    	String bldId = null;
    	boolean follow = Boolean.parseBoolean(showBuildLogs);
    	if (chatty)
    		listener.getLogger().println("\nOpenShiftBuilder logger follow " + follow);
    	
    	// get oc client (sometime REST, sometimes Exec of oc command
    	IClient client = new ClientFactory().create(apiURL, auth);
    	
    	if (client != null) {
    		// seed the auth
        	client.setAuthorizationStrategy(bearerToken);
        	
			long startTime = System.currentTimeMillis();
			boolean skipBC = buildName != null && buildName.length() > 0;
        	IBuildConfig bc = null;
        	IBuild prevBld = null;
        	if (!skipBC) {
        		bc = client.get(ResourceKind.BUILD_CONFIG, bldCfg, namespace);
        	} else {
        		prevBld = client.get(ResourceKind.BUILD, buildName, namespace);
        	}
        	

        	if (chatty)
        		listener.getLogger().println("\nOpenShiftBuilder build config retrieved " + bc + " buildName " + buildName);
        	
        	if (bc != null || prevBld != null) {
    			
        		// Trigger / start build
    			IBuild bld = null;
    			if (bc != null) {
    				if (commitID != null && commitID.length() > 0) {
    					final String cid = commitID;
            			bld = bc.accept(new CapabilityVisitor<IBuildTriggerable, IBuild>() {
        				    public IBuild visit(IBuildTriggerable triggerable) {
        				 		return triggerable.trigger(cid);
        				 	}
        				 }, null);
    				} else {
            			bld = bc.accept(new CapabilityVisitor<IBuildTriggerable, IBuild>() {
        				    public IBuild visit(IBuildTriggerable triggerable) {
        				 		return triggerable.trigger();
        				 	}
        				 }, null);
    				}
    			} else if (prevBld != null) {
    				bld = prevBld.accept(new CapabilityVisitor<IBuildTriggerable, IBuild>() {
	    				public IBuild visit(IBuildTriggerable triggerable) {
	    					return triggerable.trigger();
	    				}
	    			}, null);
    			}
    			
    			
    			if(bld == null) {
    				listener.getLogger().println("\n\nBUILD STEP EXIT:  OpenShiftBuilder triggered build is null");
    				return false;
    			} else {
    				bldId = bld.getName();
    				if (chatty)
    					listener.getLogger().println("\nOpenShiftBuilder triggered build id is " + bldId);
    				
    				
    				boolean foundPod = false;
    				String bldState = null;
    				startTime = System.currentTimeMillis();
					if (chatty)
						listener.getLogger().println("\nnOpenShiftBuilder  wait time " + getDescriptor().getWait());
    				
    				// Now find build Pod, attempt to dump the logs to the Jenkins console
    				while (!foundPod && startTime > (System.currentTimeMillis() - getDescriptor().getWait())) {
    					
    					// fetch current list of pods ... this has proven to not be immediate in finding latest
    					// entries when compared with say running oc from the cmd line
        				List<IPod> pods = client.list(ResourceKind.POD, namespace);
        				for (IPod pod : pods) {
        					if (chatty)
        						listener.getLogger().println("\nOpenShiftBuilder found pod " + pod.getName());
     
        					// build pod starts with build id
        					if(pod.getName().startsWith(bldId)) {
        						foundPod = true;
        						if (chatty)
        							listener.getLogger().println("\nOpenShiftBuilder found build pod " + pod);
        						
        						//TODO leaving this code, commented out, in for now ... the use of the oc binary for log following allows for
        						// interactive log dumping, while simply make the REST call provides dumping of the build logs once the build is
        						// complete        						
            					// get log "retrieve" and dump build logs
//            					IPodLogRetrieval logger = pod.getCapability(IPodLogRetrieval.class);
//            					listener.getLogger().println("\n\nOpenShiftBuilder obtained pod logger " + logger);
            					
//            					if (logger != null) {
            						
            						// get internal OS Java REST Client error if access pod logs while bld is in Pending state
            						// instead of Running, Complete, or Failed
            						while (System.currentTimeMillis() < (startTime + getDescriptor().getWait())) {
            							bld = client.get(ResourceKind.BUILD, bldId, namespace);
            							bldState = bld.getStatus();
            							if (chatty)
            								listener.getLogger().println("\nOpenShiftBuilder bld state:  " + bldState);
            							if ("Pending".equals(bldState) || "New".equals(bldState)) {
            								try {
    											Thread.sleep(1000);
    										} catch (InterruptedException e) {
    										}
            							} else {
            								break;
            							}
            						}
            						
            						
            						// create stream and copy bytes
            				    	URL url = null;
            				    	try {
            							url = new URL(apiURL + "/oapi/v1/namespaces/"+namespace+"/builds/" + bldId + "/log?follow=true");
            						} catch (MalformedURLException e1) {
            							e1.printStackTrace(listener.getLogger());
            							return false;
            						}
            						UrlConnectionHttpClient urlClient = new UrlConnectionHttpClient(
            								null, "application/json", null, auth, null, null);
            						urlClient.setAuthorizationStrategy(bearerToken);
            						String response = null;
            						try {
            							response = urlClient.get(url, (int) getDescriptor().getWait());
            						} catch (SocketTimeoutException e1) {
            							e1.printStackTrace(listener.getLogger());
            							return false;
            						} catch (HttpClientException e1) {
            							e1.printStackTrace(listener.getLogger());
            							return false;
            						}
            						if (follow)
            							listener.getLogger().println(response);
            						
            						//TODO leaving this code, commented out, in for now ... the use of the oc binary for log following allows for
            						// interactive log dumping, while simply make the REST call provides dumping of the build logs once the build is
            						// complete        						
//            						InputStream logs = new BufferedInputStream(logger.getLogs(true));
//            						int b;
//            						try {
//            							// we still process the build stream if followLogs is false, as 
//            							// it is a good indication of build progress (vs. periodically polling);
//            							// we simply do not dump the data to the Jenkins console if set to false
//        								while ((b = logs.read()) != -1) {
//        									if (follow)
//        										listener.getLogger().write(b);
//        								}
//        							} catch (IOException e) {
//        								e.printStackTrace(listener.getLogger());
//        							} finally {
//        								try {
//        									logs.close();
//        								} catch (final IOException e) {
//        									e.printStackTrace(listener.getLogger());
//        								}
//        							}
            						
    								
//            					} else {
//            						listener.getLogger().println("\n\nOpenShiftBuilder logger for pod " + pod.getName() + " not available");
//            						bldState = pod.getStatus();
//            					}
        					}
        					
        					if (foundPod)
        						break;
        				}
        				
        				try {
							Thread.sleep(1000);
						} catch (InterruptedException e) {
						}
        				
    				}
    				
    				if (!foundPod) {
    					listener.getLogger().println("\n\nBUILD STEP EXIT:  OpenShiftBuilder did not find build pod for " + bldId + " in time.  If possible interrogate the OpenShift server with the oc command and inspect the server logs.");
    					return false;
    				}
    				
					while (System.currentTimeMillis() < (startTime + getDescriptor().getWait())) {
						bld = client.get(ResourceKind.BUILD, bldId, namespace);
						bldState = bld.getStatus();
						if (chatty)
							listener.getLogger().println("\nOpenShiftBuilder post bld launch bld state:  " + bldState);
						if (!bldState.equals("Complete")) {
							try {
								Thread.sleep(1000);
							} catch (InterruptedException e) {
							}
						} else {
							break;
						}
					}
    				if (bldState == null || !bldState.equals("Complete")) {
    					listener.getLogger().println("\n\nBUILD STEP EXIT:  OpenShiftBuilder build state is " + bldState + ".  If possible interrogate the OpenShift server with the oc command and inspect the server logs");
    					return false;
    				} else {
    					if (checkDeps) {    						
        					if (Deployment.didAllImagesChangeIfNeeded(bldCfg, listener, chatty, client, namespace, getDescriptor().getWait())) {
        						listener.getLogger().println("\n\nBUILD STEP EXIT:  OpenShiftBuilder exit successfully (including deploy check)");
        						return true;
        					} else {
        						listener.getLogger().println("\nBUILD STEP EXIT:  OpenShiftBuild not all deployments with ImageChange triggers based on the output of this build config triggered with new images");
        						return false;
        					}
    					} else {
    						listener.getLogger().println("\n\nBUILD STEP EXIT:  OpenShiftBuilder exit successfully (no deploy check)");
    						return true;
    					}
    				}
    				
    			}
        		
        		
        	} else {
        		listener.getLogger().println("\n\nBUILD STEP EXIT:  OpenShiftBuilder could not get build config");
        		return false;
        	}
    	} else {
    		listener.getLogger().println("\n\nBUILD STEP EXIT:  OpenShiftBuilder could not get oc client");
    		return false;
    	}

	}


	// Overridden for better type safety.
    // If your plugin doesn't really define any property on Descriptor,
    // you don't have to do this.
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    /**
     * Descriptor for {@link OpenShiftBuilder}. Used as a singleton.
     * The class is marked as public so that it can be accessed from views.
     *
     */
    @Extension // This indicates to Jenkins that this is an implementation of an extension point.
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
    	private long wait = 300000;
    	
        /**
         * To persist global configuration information,
         * simply store it in a field and call save().
         *
         * <p>
         * If you don't want fields to be persisted, use <tt>transient</tt>.
         */

        /**
         * In order to load the persisted global configuration, you have to 
         * call load() in the constructor.
         */
        public DescriptorImpl() {
            load();
        }

        /**
         * Performs on-the-fly validation of the various fields.
         *
         * @param value
         *      This parameter receives the value that the user has typed.
         * @return
         *      Indicates the outcome of the validation. This is sent to the browser.
         *      <p>
         *      Note that returning {@link FormValidation#error(String)} does not
         *      prevent the form from being saved. It just means that a message
         *      will be displayed to the user. 
         */
        public FormValidation doCheckApiURL(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.warning("Unless you specify a value here, one of the default API endpoints will be used; see this field's help or https://github.com/openshift/jenkins-plugin#common-aspects-across-the-rest-based-functions-build-steps-scm-post-build-actions for details");
            return FormValidation.ok();
        }

        public FormValidation doCheckBldCfg(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("You must set a BuildConfig name");
            return FormValidation.ok();
        }

        public FormValidation doCheckNamespace(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.warning("Unless you specify a value here, the default namespace will be used; see this field's help or https://github.com/openshift/jenkins-plugin#common-aspects-across-the-rest-based-functions-build-steps-scm-post-build-actions for details");
            return FormValidation.ok();
        }
        
        public FormValidation doCheckCheckForTriggeredDeployments(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please set trigger check");
            try {
            	Boolean.parseBoolean(value);
            } catch (Throwable t) {
            	return FormValidation.error(t.getMessage());
            }
            return FormValidation.ok();
        }
        
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project types 
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "Perform builds in OpenShift";
        }
        
        public long getWait() {
        	return wait;
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            // To persist global configuration information,
            // pull info from formData, set appropriate instance field (which should have a getter), and call save().
        	wait = formData.getLong("wait");
            save();
            return super.configure(req,formData);
        }

    }

}

