package com.openshift.jenkins.plugins.pipeline.model;

import hudson.model.TaskListener;

import java.util.List;
import java.util.Map;

import net.sf.json.JSONObject;

import org.jboss.dmr.ModelNode;
import org.yaml.snakeyaml.Yaml;

import com.openshift.jenkins.plugins.pipeline.MessageConstants;
import com.openshift.jenkins.plugins.pipeline.OpenShiftApiObjHandler;
import com.openshift.restclient.IClient;
import com.openshift.restclient.model.IResource;


public interface IOpenShiftApiObjHandler extends IOpenShiftPlugin {
	
    default String fetchApiJsonFromApiServer(boolean chatty, TaskListener listener, 
        Map<String,String> overrides, String apiDomain) {
        return httpGet(chatty, listener, overrides, this.getApiURL(overrides) + "/swaggerapi" + apiDomain + "/v1");
    }
    
    default void importJsonOfApiTypes(boolean chatty, TaskListener listener, 
    		Map<String,String> overrides, String apiDomain, String json) {
    	if (json == null)
    		return;
    	ModelNode oapis = ModelNode.fromJSONString(json);
    	ModelNode apis = oapis.get("apis");
    	List<ModelNode> apiList = apis.asList();
    	for (ModelNode api : apiList) {
    		String path = api.get("path").asString();
    		ModelNode operations = api.get("operations");
    		List<ModelNode> operationList = operations.asList();
    		for (ModelNode operation : operationList) {
    			String type = operation.get("type").asString();
    			String method = operation.get("method").asString();
    			if (type.startsWith("v1.") && method.equalsIgnoreCase("POST")) {
    				String coreType = type.substring(3);
    				if (!OpenShiftApiObjHandler.apiMap.containsKey(coreType)) {
        				String typeStrForURL = null;
        				String[] pathPieces = path.split("/");
        				for (String pathPiece : pathPieces) {
        					if (pathPiece.equalsIgnoreCase(coreType + "s") || pathPiece.equalsIgnoreCase(coreType)) {
        						typeStrForURL = pathPiece;
        						break;
        					}
        				}
        				if (typeStrForURL != null) {
        					if (chatty) listener.getLogger().println("\nOpenShiftCreator: adding from API server swagger endpoint new type " + coreType + " with url str " + typeStrForURL + " to domain " + apiDomain);
        					OpenShiftApiObjHandler.apiMap.put(coreType, new String[]{apiDomain, typeStrForURL});
        				}
    				}
    			}
    		}
    	}
    	
    }
    
	//TODO openshift-restclient-java's IApiTypeMapper is not really accessible from our perspective,
	//without accessing/recreating the underlying OkHttpClient, so until that changes, we use our own
	// type mapping
    default void updateApiTypes(boolean chatty, TaskListener listener, Map<String,String>overrides) {
		importJsonOfApiTypes(chatty, listener, overrides, OpenShiftApiObjHandler.oapi, fetchApiJsonFromApiServer(chatty, listener, overrides, OpenShiftApiObjHandler.oapi));
		importJsonOfApiTypes(chatty, listener, overrides, OpenShiftApiObjHandler.api, fetchApiJsonFromApiServer(chatty, listener, overrides, OpenShiftApiObjHandler.api));
    }
    
    default ModelNode hydrateJsonYaml(String jsonyaml, TaskListener listener) {
    	// construct json/yaml node
    	ModelNode resources = null;
    	try {
    		resources = ModelNode.fromJSONString(jsonyaml);
    	} catch (Exception e) {
    	    Yaml yaml= new Yaml();
    	    Map<String,Object> map = (Map<String, Object>) yaml.load(jsonyaml);
    	    JSONObject jsonObj = JSONObject.fromObject(map);
    	    try {
    	    	resources = ModelNode.fromJSONString(jsonObj.toString());
    	    } catch (Throwable t) {
    	    	if (listener != null)
    	    		t.printStackTrace(listener.getLogger());
    	    }
    	}
    	return resources;
    }
    
    default int[] deleteAPIObjs(IClient client, TaskListener listener, String namespace, String type, String key, List<Map<String,String>> listOfLabels, boolean chatty) {
    	int[] ret = new int[2];
    	int deleted = 0;
    	int failed = 0;

    	if (chatty)
    		listener.getLogger().println(String.format("deleteAPIObjs with namespace %s type %s key %s labels %s", namespace, type, key, listOfLabels));
    	if (type != null && key != null) {
			IResource resource = client.get(type, key, namespace);
    		if (resource != null) {
    			client.delete(resource);
    			listener.getLogger().println(String.format(MessageConstants.DELETED_OBJ, type, key));
    			deleted++;
    		} else {
    			listener.getLogger().println(String.format(MessageConstants.FAILED_DELETE_OBJ, type, key));
    			failed++;
    		}
    	}
    	
    	if (listOfLabels != null && listOfLabels.size() > 0) {
    		for (Map<String,String> labels : listOfLabels) {
    			if (chatty)
    				listener.getLogger().println(String.format("deleteAPIObjs calling list with type %s namespace %s labels %s", type, namespace, labels));
    			List<IResource> resources = client.list(type, namespace, labels);
    			for (IResource resource : resources) {
    				if (resource != null) {
    					if (chatty)
    						listener.getLogger().println("deleteAPIObjs calling delete on " + resource.toJson(false));
    					client.delete(resource);
    					listener.getLogger().println(String.format(MessageConstants.DELETED_OBJ, type, resource.getName()));
    					deleted++;
    				}
    			}
    		}
    	}
    	
    	ret[0] = deleted;
    	ret[1] = failed;
    	return ret;
    }
}
