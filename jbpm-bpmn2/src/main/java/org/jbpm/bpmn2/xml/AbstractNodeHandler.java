/**
 * Copyright 2010 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jbpm.bpmn2.xml;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.drools.compiler.compiler.xml.XmlDumper;
import org.drools.compiler.rule.builder.dialect.java.JavaDialect;
import org.drools.core.process.core.datatype.DataType;
import org.drools.core.process.core.datatype.impl.type.BooleanDataType;
import org.drools.core.process.core.datatype.impl.type.FloatDataType;
import org.drools.core.process.core.datatype.impl.type.IntegerDataType;
import org.drools.core.process.core.datatype.impl.type.ObjectDataType;
import org.drools.core.process.core.datatype.impl.type.StringDataType;
import org.drools.core.xml.BaseAbstractHandler;
import org.drools.core.xml.ExtensibleXmlParser;
import org.drools.core.xml.Handler;
import org.jbpm.bpmn2.core.Association;
import org.jbpm.bpmn2.core.Definitions;
import org.jbpm.bpmn2.core.Error;
import org.jbpm.bpmn2.core.ItemDefinition;
import org.jbpm.bpmn2.core.Lane;
import org.jbpm.bpmn2.core.SequenceFlow;
import org.jbpm.compiler.xml.ProcessBuildData;
import org.jbpm.process.core.ContextContainer;
import org.jbpm.process.core.context.exception.CompensationHandler;
import org.jbpm.process.core.context.exception.CompensationScope;
import org.jbpm.process.core.context.variable.Variable;
import org.jbpm.ruleflow.core.RuleFlowProcess;
import org.jbpm.workflow.core.DroolsAction;
import org.jbpm.workflow.core.Node;
import org.jbpm.workflow.core.NodeContainer;
import org.jbpm.workflow.core.WorkflowProcess;
import org.jbpm.workflow.core.impl.DroolsConsequenceAction;
import org.jbpm.workflow.core.impl.ExtendedNodeImpl;
import org.jbpm.workflow.core.impl.NodeImpl;
import org.jbpm.workflow.core.node.ActionNode;
import org.jbpm.workflow.core.node.EndNode;
import org.jbpm.workflow.core.node.EventSubProcessNode;
import org.jbpm.workflow.core.node.ForEachNode;
import org.jbpm.workflow.core.node.WorkItemNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

public abstract class AbstractNodeHandler extends BaseAbstractHandler implements Handler {

    protected static final Logger logger = LoggerFactory.getLogger(AbstractNodeHandler.class);
    
    protected final static String EOL = System.getProperty( "line.separator" );
    protected Map<String, String> dataInputs = new HashMap<String, String>();
    protected Map<String, String> dataOutputs = new HashMap<String, String>();
    protected Map<String, String> inputAssociation = new HashMap<String, String>();
    protected Map<String, String> outputAssociation = new HashMap<String, String>();

    public AbstractNodeHandler() {
        initValidParents();
        initValidPeers();
        this.allowNesting = true;
    }
    
    protected void initValidParents() {
        this.validParents = new HashSet<Class<?>>();
        this.validParents.add(NodeContainer.class);
    }
    
    protected void initValidPeers() {
        this.validPeers = new HashSet<Class<?>>();
        this.validPeers.add(null);
        this.validPeers.add(Lane.class);
        this.validPeers.add(Variable.class);
        this.validPeers.add(Node.class);
        this.validPeers.add(SequenceFlow.class);
        this.validPeers.add(Lane.class);
        this.validPeers.add(Association.class);
    }

    public Object start(final String uri, final String localName, final Attributes attrs,
                        final ExtensibleXmlParser parser) throws SAXException {
        parser.startElementBuilder( localName, attrs );
        final Node node = createNode(attrs);
        String id = attrs.getValue("id");
        node.setMetaData("UniqueId", id);
        final String name = attrs.getValue("name");
        node.setName(name);
        if ("true".equalsIgnoreCase(System.getProperty("jbpm.v5.id.strategy"))) {
            try {
                // remove starting _
                id = id.substring(1);
                // remove ids of parent nodes
                id = id.substring(id.lastIndexOf("-") + 1);
                node.setId(new Integer(id));
            } catch (NumberFormatException e) {
                // id is not in the expected format, generating a new one
                long newId = 0;
                NodeContainer nodeContainer = (NodeContainer) parser.getParent();
                for (org.kie.api.definition.process.Node n: nodeContainer.getNodes()) {
                    if (n.getId() > newId) {
                        newId = n.getId();
                    }
                }
                ((org.jbpm.workflow.core.Node) node).setId(++newId);
            }
        } else {
            AtomicInteger idGen = (AtomicInteger) parser.getMetaData().get("idGen");
            node.setId(idGen.getAndIncrement());
        }
        return node;
    }

    protected abstract Node createNode(Attributes attrs);

    public Object end(final String uri, final String localName,
                      final ExtensibleXmlParser parser) throws SAXException {
        final Element element = parser.endElementBuilder();
        Node node = (Node) parser.getCurrent();
        handleNode(node, element, uri, localName, parser);
        NodeContainer nodeContainer = (NodeContainer) parser.getParent();
        nodeContainer.addNode(node);
        return node;
    }
    
    protected void handleNode(final Node node, final Element element, final String uri, 
                              final String localName, final ExtensibleXmlParser parser)
    	throws SAXException {
        final String x = element.getAttribute("x");
        if (x != null && x.length() != 0) {
            try {
                node.setMetaData("x", new Integer(x));
            } catch (NumberFormatException exc) {
                throw new SAXParseException("<" + localName + "> requires an Integer 'x' attribute", parser.getLocator());
            }
        }
        final String y = element.getAttribute("y");
        if (y != null && y.length() != 0) {
            try {
                node.setMetaData("y", new Integer(y));
            } catch (NumberFormatException exc) {
                throw new SAXParseException("<" + localName + "> requires an Integer 'y' attribute", parser.getLocator());
            }
        }
        final String width = element.getAttribute("width");
        if (width != null && width.length() != 0) {
            try {
                node.setMetaData("width", new Integer(width));
            } catch (NumberFormatException exc) {
                throw new SAXParseException("<" + localName + "> requires an Integer 'width' attribute", parser.getLocator());
            }
        }
        final String height = element.getAttribute("height");
        if (height != null && height.length() != 0) {
            try {
                node.setMetaData("height", new Integer(height));
            } catch (NumberFormatException exc) {
                throw new SAXParseException("<" + localName + "> requires an Integer 'height' attribute", parser.getLocator());
            }
        }
    }
    
    public abstract void writeNode(final Node node, final StringBuilder xmlDump,
    		                       final int metaDataType);
    
    protected void writeNode(final String name, final Node node, 
    		                 final StringBuilder xmlDump, int metaDataType) {
    	xmlDump.append("    <" + name + " "); 
        xmlDump.append("id=\"" + XmlBPMNProcessDumper.getUniqueNodeId(node) + "\" ");
        if (node.getName() != null) {
            xmlDump.append("name=\"" + XmlBPMNProcessDumper.replaceIllegalCharsAttribute(node.getName()) + "\" ");
        }
        if (metaDataType == XmlBPMNProcessDumper.META_DATA_AS_NODE_PROPERTY) {
            Integer x = (Integer) node.getMetaData().get("x");
            Integer y = (Integer) node.getMetaData().get("y");
            Integer width = (Integer) node.getMetaData().get("width");
            Integer height = (Integer) node.getMetaData().get("height");
            if (x != null && x != 0) {
                xmlDump.append("g:x=\"" + x + "\" ");
            }
            if (y != null && y != 0) {
                xmlDump.append("g:y=\"" + y + "\" ");
            }
            if (width != null && width != -1) {
                xmlDump.append("g:width=\"" + width + "\" ");
            }
            if (height != null && height != -1) {
                xmlDump.append("g:height=\"" + height + "\" ");
            }
        }
    }
    
    protected void endNode(final StringBuilder xmlDump) {
        xmlDump.append("/>" + EOL);
    }

    protected void endNode(final String name, final StringBuilder xmlDump) {
        xmlDump.append("    </" + name + ">" + EOL);
    }
    
    protected void handleScript(final ExtendedNodeImpl node, final Element element, String type) {
        NodeList nodeList = element.getChildNodes();
        for (int i = 0; i < nodeList.getLength(); i++) {
        	if (nodeList.item(i) instanceof Element) {
	        	Element xmlNode = (Element) nodeList.item(i);
	        	String nodeName = xmlNode.getNodeName();
	        	if (nodeName.equals("extensionElements")) {
	                NodeList subNodeList = xmlNode.getChildNodes();
	                for (int j = 0; j < subNodeList.getLength(); j++) {
	                	org.w3c.dom.Node subXmlNode = subNodeList.item(j);
	                	if(subXmlNode.getNodeName().contains(type + "-script")) {
	                		List<DroolsAction> actions = node.getActions(type);
				    		if (actions == null) {
				    			actions = new ArrayList<DroolsAction>();
				            	node.setActions(type, actions);
				    		}
				        	DroolsAction action = extractScript((Element) subXmlNode);
				        	actions.add(action);
	                	}
			    	}
	            }
        	}
        }
    }

    public static DroolsAction extractScript(Element xmlNode) {
    	String dialect = "mvel";
    	if ("http://www.java.com/java".equals(xmlNode.getAttribute("scriptFormat"))) {
    		dialect = "java";
    	}
		NodeList subNodeList = xmlNode.getChildNodes();
        for (int j = 0; j < subNodeList.getLength(); j++) {
        	if (subNodeList.item(j) instanceof Element) {
	        	Element subXmlNode = (Element) subNodeList.item(j);
	        	if ("script".equals(subXmlNode.getNodeName())) {
	        		String consequence = subXmlNode.getTextContent();
	        		DroolsConsequenceAction action = new DroolsConsequenceAction(dialect, consequence);
	        		return action;
	        	}
        	}
    	}
		return new DroolsConsequenceAction("mvel", "");
    }
    
    protected void writeScripts(ExtendedNodeImpl node, final StringBuilder xmlDump) {
    	if (node.containsActions()) {
    		xmlDump.append("      <extensionElements>" + EOL);
    		writeScripts("onEntry", node.getActions("onEntry"), xmlDump);
    		writeScripts("onExit", node.getActions("onExit"), xmlDump);
    		xmlDump.append("      </extensionElements>" + EOL);
    	}
    }
    
    protected void writeScripts(final String type, List<DroolsAction> actions, final StringBuilder xmlDump) {
    	if (actions != null && actions.size() > 0) {
	    	for (DroolsAction action: actions) {
	    		writeScript(action, type, xmlDump);
	    	}
    	}
    }
    
    public static void writeScript(final DroolsAction action, String type, final StringBuilder xmlDump) {
    	if (action instanceof DroolsConsequenceAction) {
    		DroolsConsequenceAction consequenceAction = (DroolsConsequenceAction) action;
    		xmlDump.append("        <tns:" + type + "-script");
            String name = consequenceAction.getName();
            if (name != null) {
                xmlDump.append(" name=\"" + name + "\"");
            }
            String dialect = consequenceAction.getDialect();
            if (JavaDialect.ID.equals(dialect)) {
                xmlDump.append(" scriptFormat=\"" + XmlBPMNProcessDumper.JAVA_LANGUAGE + "\"");
            }
            String consequence = consequenceAction.getConsequence();
            if (consequence != null) {
                xmlDump.append(">" + EOL + 
                    "          <tns:script>" + XmlDumper.replaceIllegalChars(consequence.trim()) + "</tns:script>" + EOL);
                xmlDump.append("        </tns:" + type + "-script>" + EOL);
            } else {
            	xmlDump.append("/>" + EOL);
            }
    	} else {
    		throw new IllegalArgumentException(
				"Unknown action " + action);
    	}
    }
    
    protected void readIoSpecification(org.w3c.dom.Node xmlNode, Map<String, String> dataInputs, Map<String, String> dataOutputs) {
        org.w3c.dom.Node subNode = xmlNode.getFirstChild();
        while (subNode instanceof Element) {
            String subNodeName = subNode.getNodeName();
            if ("dataInput".equals(subNodeName)) {
                String id = ((Element) subNode).getAttribute("id");
                String inputName = ((Element) subNode).getAttribute("name");
                dataInputs.put(id, inputName);
            }
            if ("dataOutput".equals(subNodeName)) {
                String id = ((Element) subNode).getAttribute("id");
                String outputName = ((Element) subNode).getAttribute("name");
                dataOutputs.put(id, outputName);
            }
            subNode = subNode.getNextSibling();
        }
    }
    
    protected void readDataInputAssociation(org.w3c.dom.Node xmlNode, Map<String, String> forEachNodeInputAssociation) {
        // sourceRef
        org.w3c.dom.Node subNode = xmlNode.getFirstChild();
        if ("sourceRef".equals(subNode.getNodeName())) {
            String source = subNode.getTextContent();
            // targetRef
            subNode = subNode.getNextSibling();
            String target = subNode.getTextContent();
            forEachNodeInputAssociation.put(target, source);
        }
    }
    
    protected void readDataOutputAssociation(org.w3c.dom.Node xmlNode, Map<String, String> forEachNodeOutputAssociation) {
        // sourceRef
        org.w3c.dom.Node subNode = xmlNode.getFirstChild();
        if ("sourceRef".equals(subNode.getNodeName())) {
            String source = subNode.getTextContent();
            // targetRef
            subNode = subNode.getNextSibling();
            String target = subNode.getTextContent();
            forEachNodeOutputAssociation.put(source, target);
        }
    }
    
    @SuppressWarnings("unchecked")
    protected void readMultiInstanceLoopCharacteristics(org.w3c.dom.Node xmlNode, ForEachNode forEachNode, ExtensibleXmlParser parser) {
        
        // sourceRef
        org.w3c.dom.Node subNode = xmlNode.getFirstChild();
        while (subNode != null) {
            String nodeName = subNode.getNodeName();
            if ("inputDataItem".equals(nodeName)) {
                String variableName = ((Element) subNode).getAttribute("id");
                String itemSubjectRef = ((Element) subNode).getAttribute("itemSubjectRef");
                DataType dataType = null;
                Map<String, ItemDefinition> itemDefinitions = (Map<String, ItemDefinition>)
                    ((ProcessBuildData) parser.getData()).getMetaData("ItemDefinitions");
                dataType = getDataType(itemSubjectRef, itemDefinitions);
                
                if (variableName != null && variableName.trim().length() > 0) {
                    forEachNode.setVariable(variableName, dataType);
                }
            } else if ("outputDataItem".equals(nodeName)) {
                String variableName = ((Element) subNode).getAttribute("id");
                String itemSubjectRef = ((Element) subNode).getAttribute("itemSubjectRef");
                DataType dataType = null;
                Map<String, ItemDefinition> itemDefinitions = (Map<String, ItemDefinition>)
                    ((ProcessBuildData) parser.getData()).getMetaData("ItemDefinitions");
                dataType = getDataType(itemSubjectRef, itemDefinitions);
                
                if (variableName != null && variableName.trim().length() > 0) {
                    forEachNode.setOutputVariable(variableName, dataType);
                }
            } else if ("loopDataOutputRef".equals(nodeName)) {
                
                String outputDataRef = ((Element) subNode).getTextContent();
                
                if (outputDataRef != null && outputDataRef.trim().length() > 0) {
                    String collectionName = outputAssociation.get(outputDataRef);
                    if (collectionName == null) {
                        collectionName = dataOutputs.get(outputDataRef);
                    }
                    forEachNode.setOutputCollectionExpression(collectionName);
                }
                
            } else if ("loopDataInputRef".equals(nodeName)) {
                
                String inputDataRef = ((Element) subNode).getTextContent();
               
                if (inputDataRef != null && inputDataRef.trim().length() > 0) {
                    String collectionName = inputAssociation.get(inputDataRef);
                    if (collectionName == null) {
                        collectionName = dataInputs.get(inputDataRef);
                    }
                    forEachNode.setCollectionExpression(collectionName);
                }
                
            }
            subNode = subNode.getNextSibling();
        }
    }
    
    protected DataType getDataType(String itemSubjectRef, Map<String, ItemDefinition> itemDefinitions) {
        DataType dataType = new ObjectDataType();
        if (itemDefinitions == null) {
            return dataType;
        }
        ItemDefinition itemDefinition = itemDefinitions.get(itemSubjectRef);
        if (itemDefinition != null) {
            String structureRef = itemDefinition.getStructureRef();
            
            if ("java.lang.Boolean".equals(structureRef) || "Boolean".equals(structureRef)) {
                dataType = new BooleanDataType();
                
            } else if ("java.lang.Integer".equals(structureRef) || "Integer".equals(structureRef)) {
                dataType = new IntegerDataType();
                
            } else if ("java.lang.Float".equals(structureRef) || "Float".equals(structureRef)) {
                dataType = new FloatDataType();
                
            } else if ("java.lang.String".equals(structureRef) || "String".equals(structureRef)) {
                dataType = new StringDataType();
                
            } else if ("java.lang.Object".equals(structureRef) || "Object".equals(structureRef)) {
                dataType = new ObjectDataType(structureRef);
                
            } else {
                dataType = new ObjectDataType(structureRef);
            }
            
        }
        return dataType;
    }
    
    protected String getErrorIdForErrorCode(String errorCode, Node node) { 
        org.kie.api.definition.process.NodeContainer parent = node.getNodeContainer();
        while( ! (parent instanceof RuleFlowProcess) && parent instanceof Node ) { 
            parent = ((Node) parent).getNodeContainer();
        }
        if( ! (parent instanceof RuleFlowProcess) ) { 
           throw new RuntimeException( "This should never happen: !(parent instanceof RuleFlowProcess): parent is " + parent.getClass().getSimpleName() );
        }
        List<Error> errors = ((Definitions) ((RuleFlowProcess) parent).getMetaData("Definitions")).getErrors();
        Error error = null;
        for( Error listError : errors ) { 
            if( errorCode.equals(listError.getErrorCode()) ) {
                error = listError;
                break;
            }
        }
        if (error == null) {
            throw new IllegalArgumentException("Could not find error with errorCode " + errorCode);
        }
        return error.getId();
    }
    
    protected void handleThrowCompensationEventNode(final Node node, final Element element,
            final String uri, final String localName, final ExtensibleXmlParser parser) { 
        org.w3c.dom.Node xmlNode = element.getFirstChild();
        assert node instanceof ActionNode || node instanceof EndNode 
             : "Node is neither an ActionNode nor an EndNode but a " + node.getClass().getSimpleName();
        while (xmlNode != null) {
            if ("compensateEventDefinition".equals(xmlNode.getNodeName())) {
                String activityRef = ((Element) xmlNode).getAttribute("activityRef");
                if (activityRef == null ) { 
                    activityRef = "";
                }
                node.setMetaData("compensation-activityRef", activityRef);

                /**
                 * waitForCompletion: 
                 * BPMN 2.0 Spec, p. 304: 
                 * "By default, compensation is triggered synchronously, that is the compensation throw event 
                 *  waits for the completion of the triggered compensation handler. 
                 *  Alternatively, compensation can be triggered without waiting for its completion, 
                 *  by setting the throw compensation event's waitForCompletion attribute to false."
                 */
                String nodeId = (String) node.getMetaData().get("UniqueId");
                String waitForCompletionString = ((Element) xmlNode).getAttribute("waitForCompletion");
                boolean waitForCompletion = true;
                if( waitForCompletionString != null && waitForCompletionString.length() > 0 ) { 
                    waitForCompletion = Boolean.parseBoolean(waitForCompletionString);
                }
                if( ! waitForCompletion ) { 
                    throw new IllegalArgumentException("Asynchronous compensation [" + nodeId + ", " + node.getName() 
                            + "] is not yet supported!");
                }
                
            }
            xmlNode = xmlNode.getNextSibling();
        }
    }
    

}
