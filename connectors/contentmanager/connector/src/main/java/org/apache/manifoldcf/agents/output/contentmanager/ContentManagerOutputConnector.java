package org.apache.manifoldcf.agents.output.contentmanager;

import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;

import java.io.IOException;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.config.SocketConfig;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.protocol.HttpRequestExecutor;
import org.apache.http.util.EntityUtils;
import org.apache.http.impl.client.HttpClients;
import org.apache.manifoldcf.agents.interfaces.IOutputAddActivity;
import org.apache.manifoldcf.agents.interfaces.RepositoryDocument;
import org.apache.manifoldcf.agents.interfaces.ServiceInterruption;
import org.apache.manifoldcf.agents.output.BaseOutputConnector;
import org.apache.manifoldcf.core.interfaces.ManifoldCFException;
import org.apache.manifoldcf.core.interfaces.VersionContext;
import org.apache.manifoldcf.crawler.system.Logging;

public class ContentManagerOutputConnector extends BaseOutputConnector {

	private HttpClientConnectionManager connectionManager = null;
	private HttpClient client = null;
	
	public ContentManagerOutputConnector() {
		
	}

	protected HttpClient getSession() throws ManifoldCFException 
	{
		if (client == null)
		{
			int socketTimeout = 900000;
			int connectionTimeout = 60000;
	
			connectionManager = new PoolingHttpClientConnectionManager();
			
			CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
			
			RequestConfig.Builder requestBuilder = RequestConfig.custom()
				.setCircularRedirectsAllowed(true)
				.setSocketTimeout(socketTimeout)
				.setExpectContinueEnabled(true)
				.setConnectTimeout(connectionTimeout)
				.setConnectionRequestTimeout(socketTimeout);
				
			client = HttpClients.custom()
				.setConnectionManager(connectionManager)
				.setMaxConnTotal(1)
				.disableAutomaticRetries()
				.setDefaultRequestConfig(requestBuilder.build())
				.setDefaultSocketConfig(SocketConfig.custom()
				.setSoTimeout(socketTimeout)
				.build())
				.setDefaultCredentialsProvider(credentialsProvider)
				.setRequestExecutor(new HttpRequestExecutor(socketTimeout))
				.build();
		}

		return client;
	}

	@Override
	public int addOrReplaceDocumentWithException(String documentURI, VersionContext pipelineDescription, RepositoryDocument document, String authorityNameString, IOutputAddActivity activities)
	 throws ManifoldCFException, ServiceInterruption, IOException
	{
	    HttpClient client = getSession();

	    Logging.connectors.error("YO DUDE THE DOCUMENT FILENAME WAS " + document.getFileName());
	    
	    String name = document.getFileName();
	    
	    if (name == null || name.isEmpty()) {
	    	name = "document.getFileName() was blank heres a fake title";
	    }
	    
	    StringEntity requestEntity = new StringEntity("{ \"RecordRecordType\": \"2\", \"RecordTypedTitle\": { \"Value\": \"" + document.getFileName() + "\"  } }", ContentType.APPLICATION_JSON);
	    
	    Logging.connectors.error(requestEntity.toString());
	    
	    HttpPost postMethod = new HttpPost("http://localhost/CMServiceAPI/Record");
	    postMethod.setEntity(requestEntity);
	    
	    HttpResponse response = client.execute(postMethod);

	    HttpEntity entity = response.getEntity();
		String responseString = EntityUtils.toString(entity, "UTF-8");
	    Logging.connectors.error(responseString);
	    
	    return DOCUMENTSTATUS_ACCEPTED;
	}
}
