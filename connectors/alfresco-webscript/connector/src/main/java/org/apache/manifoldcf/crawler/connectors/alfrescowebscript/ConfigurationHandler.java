/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.manifoldcf.crawler.connectors.alfrescowebscript;

import com.google.common.collect.ImmutableMultimap;
import org.alfresco.consulting.indexer.client.AlfrescoFilters;
import org.apache.commons.io.IOUtils;
import org.apache.manifoldcf.core.interfaces.*;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.util.*;

public class ConfigurationHandler {
  private static final String PARAM_PROTOCOL = "protocol";
  private static final String PARAM_HOSTNAME = "hostname";
  private static final String PARAM_ENDPOINT = "endpoint";
  private static final String PARAM_STORE_PROTOCOL = "storeprotocol";
  private static final String PARAM_ENABLE_DOCUMENT_PROCESSING = "enabledocumentprocessing";
  private static final String PARAM_STORE_ID = "storeid";
  private static final String PARAM_USERNAME = "username";
  private static final String PARAM_PASSWORD = "password";

  // Output Specification for Filtering
  /**
   * Node describing a Site
   */
  public static final String NODE_SITE = "site";
  /**
   * Attribute describing a site name
   */
  public static final String ATTRIBUTE_SITE = "site_name";

  /**
   * Node describing a MimeType
   */
  public static final String NODE_MIMETYPE = "mimetype";
  /**
   * Attribute describing a MimeType name
   */
  public static final String ATTRIBUTE_MIMETYPE = "mimetype_name";

  /**
   * Node describing an Aspect
   */
  public static final String NODE_ASPECT = "aspect";
  /**
   * Attribute describing an aspect name
   */
  public static final String ATTRIBUTE_ASPECT = "aspect_name";

  /**
   * Node describing a Metadata
   */
  public static final String NODE_METADATA = "metadata";
  /**
   * Attribute describing an aspect name
   */
  public static final String ATTRIBUTE_METADATA_SOURCE = "metadata_source";
  /**
   * Attribute describing an aspect value
   */
  public static final String ATTRIBUTE_METADATA_TARGET = "metadata_value";

  public static final ImmutableMultimap<String, String> SPECIFICATION_MAP =
      ImmutableMultimap.<String, String>builder().
          put(NODE_SITE, ATTRIBUTE_SITE).
          put(NODE_MIMETYPE, ATTRIBUTE_MIMETYPE).
          put(NODE_ASPECT, ATTRIBUTE_ASPECT).
          put(NODE_METADATA, ATTRIBUTE_METADATA_SOURCE).
          put(NODE_METADATA, ATTRIBUTE_METADATA_TARGET).build();

  private static final String EDIT_CONFIG_HEADER = "editConfiguration.js";
  private static final String EDIT_CONFIG_SERVER = "editConfiguration_Server.html";
  private static final String VIEW_CONFIG = "viewConfiguration.html";

  private static final Map<String, String> DEFAULT_CONFIGURATION_PARAMETERS = new HashMap<String, String>();

  static {
    DEFAULT_CONFIGURATION_PARAMETERS.put(PARAM_PROTOCOL, "http");
    DEFAULT_CONFIGURATION_PARAMETERS.put(PARAM_HOSTNAME, "localhost");
    DEFAULT_CONFIGURATION_PARAMETERS.put(PARAM_ENDPOINT, "/alfresco/service");
    DEFAULT_CONFIGURATION_PARAMETERS.put(PARAM_STORE_PROTOCOL, "workspace");
    DEFAULT_CONFIGURATION_PARAMETERS.put(PARAM_ENABLE_DOCUMENT_PROCESSING, "true");
    DEFAULT_CONFIGURATION_PARAMETERS.put(PARAM_STORE_ID, "SpacesStore");
    DEFAULT_CONFIGURATION_PARAMETERS.put(PARAM_USERNAME, "");
    DEFAULT_CONFIGURATION_PARAMETERS.put(PARAM_PASSWORD, "");
  }

  private ConfigurationHandler() {
  }

  public static void outputConfigurationHeader(IThreadContext threadContext,
                                               IHTTPOutput out, Locale locale, ConfigParams parameters,
                                               List<String> tabsArray) throws ManifoldCFException, IOException {
    tabsArray.add(Messages.getString(locale,"Alfresco.Server"));
    Map<String, Object> paramMap = new HashMap<String, Object>();
    fillInParameters(paramMap, out, parameters);
    Messages.outputResourceWithVelocity(out, locale, EDIT_CONFIG_HEADER, paramMap);
  }

  private static void fillInParameters(Map<String, Object> paramMap,
                                       IHTTPOutput out, ConfigParams parameters) {
    for (Map.Entry<String, String> parameter : DEFAULT_CONFIGURATION_PARAMETERS
        .entrySet()) {
      String paramName = parameter.getKey();
      if (paramName.endsWith("password")) {
        String paramValue = parameters.getObfuscatedParameter(paramName);
        if (paramValue == null) {
          paramValue = parameter.getValue();
        }
        paramMap.put(paramName, out.mapPasswordToKey(paramValue));
      } else {
        String paramValue = parameters.getParameter(paramName);
        if (paramValue == null) {
          paramValue = parameter.getValue();
        }
        paramMap.put(paramName, paramValue);
      }
    }
  }

  public static void outputConfigurationBody(IThreadContext threadContext,
                                             IHTTPOutput out, Locale locale, ConfigParams parameters, String tabName)
      throws ManifoldCFException, IOException {
    Map<String, Object> paramMap = new HashMap<String, Object>();
    paramMap.put("tabName", tabName);
    fillInParameters(paramMap, out, parameters);
    Messages.outputResourceWithVelocity(out, locale, EDIT_CONFIG_SERVER, paramMap);
  }

  public static String processConfigurationPost(IThreadContext threadContext,
                                                IPostParameters variableContext, Locale locale, ConfigParams parameters)
      throws ManifoldCFException {
    for (String paramName : DEFAULT_CONFIGURATION_PARAMETERS.keySet()) {
      String paramValue = variableContext.getParameter(paramName);
      if (paramValue != null) {
        if (paramName.endsWith("password"))
          parameters.setObfuscatedParameter(paramName,variableContext.mapKeyToPassword(paramValue));
        else
          parameters.setParameter(paramName, paramValue);
      }
    }
    return null;
  }

  public static void viewConfiguration(IThreadContext threadContext,
                                       IHTTPOutput out, Locale locale, ConfigParams parameters)
      throws ManifoldCFException, IOException {
    Map<String, Object> paramMap = new HashMap<String, Object>();
    fillInParameters(paramMap, out, parameters);
    Messages.outputResourceWithVelocity(out, locale, VIEW_CONFIG, paramMap);
  }
  
  public static void outputSpecificationHeader(IHTTPOutput out, Locale locale, Specification os,
                                               int connectionSequenceNumber, List<String> tabsArray)
      throws ManifoldCFException, IOException
  {
    String seqPrefix = "s"+connectionSequenceNumber+"_";
    tabsArray.add(Messages.getString(locale, "Alfresco.FilteringConfiguration"));
    out.print(
              "<script type=\"text/javascript\">\n"+
              "<!--\n"+
              "function "+seqPrefix+"checkSpecification()\n"+
              "{\n"+
              "  return true;\n"+
              "}\n"+
              "\n");
    
    for(String node:SPECIFICATION_MAP.keySet()){
      out.print(
                "function "+seqPrefix+"add"+node+"()\n"+
                "{\n");
      Collection<String> vars = SPECIFICATION_MAP.get(node);
      for(String var:vars){
        Object[] args = new String[]{node,var};
        out.print(
                  "if (editjob."+seqPrefix+var+".value == \"\")\n"+
                  "  {\n"+
//                "    alert(\"Value of "+ node + "." + var + " can't be NULL" +"\");\n"+
                  "    alert(\""+ Messages.getBodyJavascriptString(locale, "Alfresco.ParamNullError",args) + "\");\n"+
                  "    editjob."+seqPrefix+var+".focus();\n"+
                  "    return;\n"+
                  "  }\n"
                  );
      }
          
      out.print("editjob."+seqPrefix+node+"_op.value=\"Add\";\n"+
                "  postFormSetAnchor(\""+seqPrefix+"+"+node+"\");\n"+
                "}\n"+
                "\n"+
                "function "+seqPrefix+"delete"+node+"(i)\n"+
                "{\n"+
                "  // Set the operation\n"+
                "  eval(\"editjob."+seqPrefix+node+"_\"+i+\"_op.value=\\\"Delete\\\"\");\n"+
                "  // Submit\n"+
                "  if (editjob."+seqPrefix+node+"_count.value==i)\n"+
                "    postFormSetAnchor(\""+seqPrefix+node+"\");\n"+
                "  else\n"+
                "    postFormSetAnchor(\""+seqPrefix+node+"_\"+i)\n"+
                "  // Undo, so we won't get two deletes next time\n"+
                "  eval(\"editjob."+seqPrefix+node+"_\"+i+\"_op.value=\\\"Continue\\\"\");\n"+
                "}\n"+
                "\n");
    }
        
    out.print("\n"+
              "\n"+
              "//-->\n"+
              "</script>\n");    
  }

  
  public static void outputSpecificationBody(IHTTPOutput out, Locale locale, Specification os,
      int connectionSequenceNumber, int actualSequenceNumber, String tabName)
          throws ManifoldCFException, IOException
  {
    String seqPrefix = "s"+connectionSequenceNumber+"_";
    int i;
    // Field Mapping tab
    if (tabName.equals(Messages.getString(locale, "Alfresco.FilteringConfiguration")) && connectionSequenceNumber == actualSequenceNumber)
    {
      out.print(
                  "<table class=\"displaytable\">\n"
      );
      for(String node:SPECIFICATION_MAP.keySet()){
        out.print(
                  "  <tr><td class=\"separator\" colspan=\"2\"><hr/></td></tr>\n"+
                  "  <tr>\n"+
                  "    <td class=\"description\"><nobr>" + Messages.getBodyString(locale, "Alfresco.SpecificFilteringConfiguration",new String[]{Messages.getString(locale,"Alfresco."+node)}) + "</nobr></td>\n"+
                  "    <td class=\"boxcell\">\n"+
                  "      <table class=\"formtable\">\n"+
                  "        <tr class=\"formheaderrow\">\n"+
                  "          <td class=\"formcolumnheader\"></td>\n");
        Collection<String> vars = SPECIFICATION_MAP.get(node);
        for(String var:vars){
          out.print(
                  "          <td class=\"formcolumnheader\"><nobr>" + Messages.getString(locale,"Alfresco."+var) + "</nobr></td>\n"
          );
        }
        out.print(
                  "        </tr>\n"
        );
        
        int fieldCounter = 0;
        i = 0;
        while (i < os.getChildCount()) {
          SpecificationNode sn = os.getChild(i++);
          if (sn.getType().equals(node)) {
            String prefix = seqPrefix+node+"_" + Integer.toString(fieldCounter);
            out.print(
                  "        <tr class=\""+(((fieldCounter % 2)==0)?"evenformrow":"oddformrow")+"\">\n"+
                  "          <td class=\"formcolumncell\">\n"+
                  "            <a name=\""+prefix+"\">\n"+
                  "              <input type=\"button\" value=\""+ Messages.getBodyJavascriptString(locale, "Alfresco.Delete") + "\" alt=\""+ Messages.getBodyJavascriptString(locale, "Alfresco.Delete") + ""+Integer.toString(fieldCounter+1)+"\" onclick='javascript:"+seqPrefix+"delete"+node+"("+Integer.toString(fieldCounter)+");'/>\n"+
                  "              <input type=\"hidden\" name=\""+prefix+"_op\" value=\"Continue\"/>\n");
            for(String var:vars){
              out.print(
                  "              <input type=\"hidden\" name=\""+prefix+"_"+var+"\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(sn.getAttributeValue(var))+"\"/>\n"
              );
            }

            out.print(
                  "            </a>\n"+
                  "          </td>\n"
            );
            for(String var:vars){
              out.print(
                  "          <td class=\"formcolumncell\">\n"+
                  "            <nobr>"+org.apache.manifoldcf.ui.util.Encoder.bodyEscape(sn.getAttributeValue(var))+"</nobr>\n"+
                  "          </td>\n");
            }
            out.print(
                  "        </tr>\n"
            );
            fieldCounter++;

          }
        }
        if (fieldCounter == 0)
          {
            out.print(
                  "        <tr class=\"formrow\"><td class=\"formmessage\" colspan=\""+(vars.size()+1)+"\">"+ Messages.getBodyJavascriptString(locale, "Alfresco.NoFilteringConfiguration") + "</td></tr>\n");
          }
        
        out.print(
                  "        <tr class=\"formrow\"><td class=\"formseparator\" colspan=\""+(vars.size()+1)+"\"><hr/></td></tr>\n"+
                  "        <tr class=\"formrow\">\n"+
                  "          <td class=\"formcolumncell\">\n"+
                  "            <a name=\""+seqPrefix+node+"\">\n"+
                  "              <input type=\"button\" value=\""+ Messages.getAttributeString(locale, "Alfresco.Add") + "\" alt=\""+ Messages.getAttributeString(locale, "Alfresco.Add") + " " + node + "\" onclick=\"javascript:"+seqPrefix+"add"+node+"();\"/>\n"+
                  "            </a>\n"+
                  "            <input type=\"hidden\" name=\""+seqPrefix+node+"_count\" value=\""+fieldCounter+"\"/>\n"+
                  "            <input type=\"hidden\" name=\""+seqPrefix+node+"_op\" value=\"Continue\"/>\n"+
                  "          </td>\n");
        for(String var:vars){
          out.print(
                  "          <td class=\"formcolumncell\">\n"+
                  "            <nobr><input type=\"text\" size=\"15\" name=\""+seqPrefix+var+"\" value=\"\"/></nobr>\n"+
                  "          </td>\n");
        }

        out.print(
                  "        </tr>\n"+
                  "      </table>\n"+
                  "    </td>\n"+
                  "  </tr>\n"
        );
            

      }
      out.print(
                  "</table>\n"
      );

    }
    else{
        for(String node:SPECIFICATION_MAP.keySet()){
          i = 0;
          int fieldCounter = 0;  
          while (i < os.getChildCount()) {
            SpecificationNode sn = os.getChild(i++);
            if(sn.getType().equals(node)){
            String prefix = seqPrefix+node+"_" + Integer.toString(fieldCounter);  
            for(String var:SPECIFICATION_MAP.get(node)){
              out.print(
                        "<input type=\"hidden\" name=\""+prefix+"_"+var+"\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(sn.getAttributeValue(var))+"\"/>\n");
            }
            fieldCounter++;
            }
          }
          
          out.print("<input type=\"hidden\" name=\""+seqPrefix+node+"_count\" value=\""+Integer.toString(fieldCounter)+"\"/>\n");
        }
      }
    }
      
  public static String processSpecificationPost(IPostParameters variableContext, Locale locale, Specification os,
      int connectionSequenceNumber) throws ManifoldCFException {
    // Remove old Nodes
    int i;

    String seqPrefix = "s"+connectionSequenceNumber+"_";
          
    for(String node:SPECIFICATION_MAP.keySet()){
      
      String x = variableContext.getParameter(seqPrefix+node+"_count");
      if (x != null && x.length() > 0){
        
        i = 0;
        while (i < os.getChildCount())
        {
          SpecificationNode specNode = os.getChild(i);
          if (specNode.getType().equals(node))
            os.removeChild(i);
          else
            i++;
        }

        Collection<String> vars = SPECIFICATION_MAP.get(node);

        int count = Integer.parseInt(x);
        i = 0;
        while (i < count)
        {
          String prefix = seqPrefix+node+"_"+Integer.toString(i);
          String op = variableContext.getParameter(prefix+"_op");
          if (op == null || !op.equals("Delete"))
          {
            SpecificationNode specNode = new SpecificationNode(node);
            for(String var:vars){
              String value = variableContext.getParameter(prefix+"_"+var);
              if(value == null)
                value = "";
              specNode.setAttribute(var, value);
            }
            os.addChild(os.getChildCount(), specNode);
          }
          i++;
        }

        String addop = variableContext.getParameter(seqPrefix+node+"_op");
        if (addop != null && addop.equals("Add"))
        {
          SpecificationNode specNode = new SpecificationNode(node);
          for(String var:vars){
            String value = variableContext.getParameter(seqPrefix+var);
            if(value == null)
              value = "";
            specNode.setAttribute(var, value);
          }
          os.addChild(os.getChildCount(), specNode);
        }
      }
    }

    return null;
  }
  
  public static void viewSpecification(IHTTPOutput out, Locale locale, Specification os,
                                       int connectionSequenceNumber)
      throws ManifoldCFException, IOException
  {
    int i = 0;

    for(String node:SPECIFICATION_MAP.keySet()){
      Collection<String> vars = SPECIFICATION_MAP.get(node);
      out.print(
                "\n"+
                "<table class=\"displaytable\">\n"+
                "  <tr>\n"+
                "    <td class=\"description\"><nobr>"+ Messages.getBodyString(locale, "Alfresco.SpecificFilteringConfiguration",new String[]{Messages.getString(locale,"Alfresco."+node)}) +"</nobr></td>\n"+
                "    <td class=\"boxcell\">\n"+
                "      <table class=\"formtable\">\n"+
                "        <tr class=\"formheaderrow\">\n");
      for(String var:vars)
        out.print(
                "          <td class=\"formcolumnheader\"><nobr>" + Messages.getBodyString(locale, "Alfresco."+var) + "</nobr></td>\n"
        );
              
      out.print(
                "        </tr>\n"
      );
     
      int fieldCounter = 0;
      i = 0;
     
      while (i < os.getChildCount()) {
        SpecificationNode sn = os.getChild(i++);
        if (sn.getType().equals(node)) {
          out.print(
                "        <tr class=\""+(((fieldCounter % 2)==0)?"evenformrow":"oddformrow")+"\">\n"
          );
          for(String var:vars)
            out.print(
                "          <td class=\"formcolumncell\">\n"+
                "            <nobr>"+org.apache.manifoldcf.ui.util.Encoder.bodyEscape(sn.getAttributeValue(var))+"</nobr>\n"+
                "          </td>\n"
            );
          out.print(
                "        </tr>\n"
          );
          fieldCounter++;
        }
      }

      if (fieldCounter == 0)
      {
        out.print(
                "        <tr class=\"formrow\"><td class=\"formmessage\" colspan=\""+vars.size()+"\">"+ Messages.getBodyJavascriptString(locale, "Alfresco.NoSpecificFilteringConfiguration",new String[]{node}) + "</td></tr>\n"
        );
      }
      out.print(
                "      </table>\n"+
                "    </td>\n"+
                "  </tr>\n"+
                "</table>\n");
    }
  }
  
  public static String getSpecificationVersion(Specification os){
    StringBuilder builder = new StringBuilder();
    int i = 0;
    while(i < os.getChildCount()){
      SpecificationNode node = os.getChild(i);
      Collection<String> vars = SPECIFICATION_MAP.get(node.getType());
      for(String var:vars)
        builder.append(node.getAttributeValue(var)).append("+");
      i++;
    }
    return builder.toString();
  }
  
  public static AlfrescoFilters getFilters(Specification spec) {
    AlfrescoFilters filters = new AlfrescoFilters();
    for(int i = 0; i < spec.getChildCount(); i++){
      SpecificationNode node = spec.getChild(i);
      if(node.getType().equals(NODE_SITE))
        filters.addSiteFilter(node.getAttributeValue(ATTRIBUTE_SITE));
      else if(node.getType().equals(NODE_MIMETYPE))
        filters.addMimetypeFilter(node.getAttributeValue(ATTRIBUTE_MIMETYPE));
      else if(node.getType().equals(NODE_ASPECT))
        filters.addAspectFilter(
            node.getAttributeValue(ATTRIBUTE_ASPECT));
      else if(node.getType().equals(NODE_METADATA))
        filters.addMetadataFilter(
            node.getAttributeValue(ATTRIBUTE_METADATA_SOURCE),
            node.getAttributeValue(ATTRIBUTE_METADATA_TARGET));
    }
    
    return filters;
  }
}
