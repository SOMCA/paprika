package paprika.neo4j;

import org.neo4j.cypher.javacompat.ExecutionEngine;
import org.neo4j.graphdb.*;
import paprika.entities.*;
import paprika.metrics.Metric;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by Geoffrey Hecht on 05/06/14.
 */
public class ModelToGraph {
    private GraphDatabaseService graphDatabaseService;
    private DatabaseManager databaseManager;
    private ExecutionEngine engine;
    private static final Label appLabel = DynamicLabel.label("App");
    private static final Label classLabel = DynamicLabel.label("Class");
    private static final Label methodLabel = DynamicLabel.label("Method");
    private static final Label variableLabel = DynamicLabel.label("Variable");
    private static final Label argumentLabel = DynamicLabel.label("Argument");

    private Map<PaprikaMethod,Node> methodNodeMap;
    private Map<PaprikaClass,Node> classNodeMap;
    private Map<PaprikaVariable,Node> variableNodeMap;

    private String key;

    public ModelToGraph(String DatabasePath){
        this.databaseManager = new DatabaseManager(DatabasePath);
        databaseManager.start();
        this.graphDatabaseService = databaseManager.getGraphDatabaseService();
        engine = new ExecutionEngine(graphDatabaseService);
        methodNodeMap = new HashMap<>();
        classNodeMap = new HashMap<>();
        variableNodeMap = new HashMap<>();
    }

    public Node insertApp(PaprikaApp paprikaApp){
        this.key = paprikaApp.getKey();
        Node appNode;
        try ( Transaction tx = graphDatabaseService.beginTx() ){
            appNode = graphDatabaseService.createNode(appLabel);
            appNode.setProperty("app_key",key);
            appNode.setProperty("name",paprikaApp.getName());
            appNode.setProperty("category",paprikaApp.getCategory());
            appNode.setProperty("package",paprikaApp.getPack());
            appNode.setProperty("developer",paprikaApp.getDeveloper());
            appNode.setProperty("rating",paprikaApp.getRating());
            appNode.setProperty("nb_download",paprikaApp.getNbDownload());
            appNode.setProperty("date_download",paprikaApp.getDate());
            Date date = new Date();
            SimpleDateFormat  simpleFormat = new SimpleDateFormat("yyyy-mm-dd hh:mm:ss.S");
            appNode.setProperty("date_analysis", simpleFormat.format(date));
            appNode.setProperty("size",paprikaApp.getSize());
            appNode.setProperty("price",paprikaApp.getPrice());
            for(PaprikaClass paprikaClass : paprikaApp.getPaprikaClasses()){
                appNode.createRelationshipTo(insertClass(paprikaClass),RelationTypes.APP_OWNS_CLASS);
            }
            for(Metric metric : paprikaApp.getMetrics()){
                insertMetric(metric, appNode);
            }
            tx.success();
        }
        //createIndex();
        try ( Transaction tx = graphDatabaseService.beginTx() ){
            createHierarchy(paprikaApp);
            createCallGraph(paprikaApp);
            tx.success();
        }
        return appNode;
    }

    private void insertMetric(Metric metric, Node node) {
        node.setProperty(metric.getName(),metric.getValue());
    }


    public Node insertClass(PaprikaClass paprikaClass){
        Node classNode = graphDatabaseService.createNode(classLabel);
        classNodeMap.put(paprikaClass,classNode);
        classNode.setProperty("app_key",key);
        classNode.setProperty("name",paprikaClass.getName());
        classNode.setProperty("modifier", paprikaClass.getModifier().toString().toLowerCase());
        if(paprikaClass.getParentName() != null){
            classNode.setProperty("parent_name", paprikaClass.getParentName());
        }
        for(PaprikaVariable paprikaVariable : paprikaClass.getPaprikaVariables()){
            classNode.createRelationshipTo(insertVariable(paprikaVariable),RelationTypes.CLASS_OWNS_VARIABLE);

        }
        for(PaprikaMethod paprikaMethod : paprikaClass.getPaprikaMethods()){
            classNode.createRelationshipTo(insertMethod(paprikaMethod),RelationTypes.CLASS_OWNS_METHOD);
        }
        for(Metric metric : paprikaClass.getMetrics()){
            insertMetric(metric,classNode);
        }
        return classNode;
    }

    public Node insertVariable(PaprikaVariable paprikaVariable){
        Node variableNode = graphDatabaseService.createNode(variableLabel);
        variableNodeMap.put(paprikaVariable,variableNode);
        variableNode.setProperty("app_key", key);
        variableNode.setProperty("name", paprikaVariable.getName());
        variableNode.setProperty("modifier", paprikaVariable.getModifier().toString().toLowerCase());
        variableNode.setProperty("type", paprikaVariable.getType());
        for(Metric metric : paprikaVariable.getMetrics()){
            insertMetric(metric, variableNode);
        }
        return variableNode;
    }
    
    public Node insertMethod(PaprikaMethod paprikaMethod){
        Node methodNode = graphDatabaseService.createNode(methodLabel);
        methodNodeMap.put(paprikaMethod,methodNode);
        methodNode.setProperty("app_key", key);
        methodNode.setProperty("name",paprikaMethod.getName());
        methodNode.setProperty("modifier", paprikaMethod.getModifier().toString().toLowerCase());
        methodNode.setProperty("full_name",paprikaMethod.toString());
        methodNode.setProperty("return_type",paprikaMethod.getReturnType());
        for(Metric metric : paprikaMethod.getMetrics()){
            insertMetric(metric, methodNode);
        }
        for(PaprikaVariable paprikaVariable : paprikaMethod.getUsedVariables()){
            methodNode.createRelationshipTo(variableNodeMap.get(paprikaVariable),RelationTypes.USES);
        }
        for(PaprikaArgument arg : paprikaMethod.getArguments()){
            methodNode.createRelationshipTo(insertArgument(arg),RelationTypes.METHOD_OWNS_ARGUMENT);
        }
        return methodNode;
    }

    public Node insertArgument(PaprikaArgument paprikaArgument){
        Node argNode = graphDatabaseService.createNode(argumentLabel);
        argNode.setProperty("app_key", key);
        argNode.setProperty("name", paprikaArgument.getName());
        argNode.setProperty("position", paprikaArgument.getPosition());
        return argNode;
    }

    public void createHierarchy(PaprikaApp paprikaApp) {
        for (PaprikaClass paprikaClass : paprikaApp.getPaprikaClasses()) {
            PaprikaClass parent = paprikaClass.getParent();
            if (parent != null) {
                classNodeMap.get(paprikaClass).createRelationshipTo(classNodeMap.get(parent),RelationTypes.EXTENDS);
            }
            for(PaprikaClass pInterface : paprikaClass.getInterfaces()){
                classNodeMap.get(paprikaClass).createRelationshipTo(classNodeMap.get(pInterface),RelationTypes.IMPLEMENTS);
            }
        }
    }

    public void createCallGraph(PaprikaApp paprikaApp) {
        for (PaprikaClass paprikaClass : paprikaApp.getPaprikaClasses()) {
            for (PaprikaMethod paprikaMethod : paprikaClass.getPaprikaMethods()){
                for(PaprikaMethod calledMethod : paprikaMethod.getCalledMethods()){
                    methodNodeMap.get(paprikaMethod).createRelationshipTo(methodNodeMap.get(calledMethod),RelationTypes.CALLS);
                }
            }
        }
    }
}
