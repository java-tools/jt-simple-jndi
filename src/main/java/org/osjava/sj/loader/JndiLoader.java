/*
 * Copyright (c) 2003-2005, Henri Yandell
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or 
 * without modification, are permitted provided that the 
 * following conditions are met:
 * 
 * + Redistributions of source code must retain the above copyright notice, 
 *   this list of conditions and the following disclaimer.
 * 
 * + Redistributions in binary form must reproduce the above copyright notice, 
 *   this list of conditions and the following disclaimer in the documentation 
 *   and/or other materials provided with the distribution.
 * 
 * + Neither the name of Simple-JNDI nor the names of its contributors 
 *   may be used to endorse or promote products derived from this software 
 *   without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" 
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE 
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE 
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE 
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR 
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF 
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS 
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN 
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) 
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE 
 * POSSIBILITY OF SUCH DAMAGE.
 */
package org.osjava.sj.loader;


import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.osjava.sj.loader.convert.Converter;
import org.osjava.sj.loader.convert.ConverterRegistry;
import org.osjava.sj.loader.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.naming.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 *
 */
public class JndiLoader {

    // separator, or just put them in as contexts?
    public static final String SIMPLE_DELIMITER = "org.osjava.sj.delimiter";

    // char(s) to replace : with on the filesystem in filenames
    public static final String SIMPLE_COLON_REPLACE = "org.osjava.sj.colon.replace";
    private static final Properties EMPTY_PROPERTIES = new Properties();

    private static ConverterRegistry converterRegistry = new ConverterRegistry();
    private final Properties envAsProperties;

    private Hashtable environment = new Hashtable();
    private Logger LOGGER = LoggerFactory.getLogger(this.getClass());
    public static final String FILENAME_TO_CONTEXT = "org.osjava.sj.filenameToContext";

    public JndiLoader(Hashtable env) {
        if(!env.containsKey(SIMPLE_DELIMITER)) {
            throw new IllegalArgumentException("The property " + SIMPLE_DELIMITER + " is mandatory. ");
        }
        environment = new Hashtable(env);
        Properties props = new Properties();
        props.putAll(environment);
        envAsProperties = props;
    }
    
    /**
     * Loads all .properties", .ini, .xml files in a directory or a single file into a context.
     */
    public void load(File fileOrDirectory, Context ctxt) throws NamingException, IOException {
        if (fileOrDirectory.isDirectory()) {
            loadDirectory(fileOrDirectory, ctxt, null, "");
        }
        else if (fileOrDirectory.isFile()) {
            Context tmpCtx = ctxt;
//            if (environment.containsKey(FILENAME_TO_CONTEXT)) {
//                String name = FilenameUtils.removeExtension(fileOrDirectory.getName());
//                name = handleColonReplacement(name);
//                tmpCtx = ctxt.createSubcontext(name);
//            }
            loadFile(fileOrDirectory, tmpCtx, null, "");
        }
    }

    private void loadDirectory(File directory, Context ctxt, Context parentCtxt, String subName) throws NamingException, IOException {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    String parentName = file.getName();
                    // HACK: Hack to stop it looking in .svn or CVS
                    if (parentName.equals(".svn") || parentName.equals("CVS")) {
                        return;
                    }
                    parentName = handleColonReplacement(parentName);
                    Context tmpCtxt = ctxt.createSubcontext(parentName);
                    loadDirectory(file, tmpCtxt, ctxt, parentName);
                }
                else {
                    loadFile(file, ctxt, parentCtxt, subName);
                }
            }
        }
    }

    /**
     * Handles only .properties, .ini", .xml files.
     *
     * @param file Not a directory
     */
    private void loadFile(File file, Context ctxt, Context parentCtxt, String subName) throws NamingException, IOException {
        String parentName = file.getName();
        parentName = handleColonReplacement(parentName);
        String[] extensions = new String[]{".properties", ".ini", ".xml"};
        for (String extension : extensions) {
            if (file.getName().endsWith(extension)) {
                Context subContext = ctxt;
                Properties properties = toProperties(file);
                if (isNotNamespacedTypeDefinition(properties)) {
                    // preserve the file name as object name.
                    subName = parentName.substring(0, parentName.length() - extension.length());
                    parentCtxt = subContext;
                }
                else if (!file.getName().equals("default" + extension)) {
                    parentName = parentName.substring(0, parentName.length() - extension.length());
                    subContext = ctxt.createSubcontext(parentName);
                    parentCtxt = ctxt;
                    subName = parentName;
                }
                load(properties, subContext, parentCtxt, subName);
            }
        }
    }

    /**
     * For example a DataSource definition file with properties without namespace, e. g. "type=javax.sql.DataSource" instead of "Sybase/type=javax.sql.DataSource".
     */
    boolean isNotNamespacedTypeDefinition(Properties properties) {
        for (Object k : properties.keySet()) {
            String key = (String) k;
            if (key.equals("type")) {
                return true;
            }
        }
        return false;
    }

    String handleColonReplacement(String name) {
        String colonReplace = (String) environment.get(SIMPLE_COLON_REPLACE);
        if (colonReplace != null) {
            if (name.contains(colonReplace)) {
                name = Utils.replace(name, colonReplace, ":");
            }
        }
        return name;
    }

    /**
     *
     * @return xml file: {@link XmlProperties}. ini file: {@link IniProperties}. Sonst {@link CustomProperties}.
     */
    public Properties toProperties(File file) throws IOException {
//        System.err.println("LOADING: "+file);
        AbstractProperties p;

        if(file.getName().endsWith(".xml")) {
            p = new XmlProperties();
        } else
        if(file.getName().endsWith(".ini")) {
            p = new IniProperties();
        } else {
            p = new CustomProperties();
        }

        p.setDelimiter( (String) environment.get(SIMPLE_DELIMITER) );

        FileInputStream fin = null;
        try {
            fin = new FileInputStream(file);
            p.load(fin);
            return p;
        } finally {
            if(fin != null) fin.close();
        }
    }


    /**
     * Loads a properties object into a context.
     */
    public void load(Properties properties, Context ctxt) throws NamingException {
        load(properties, ctxt, null, "");
    }

    private void load(Properties properties, Context subContext, Context parentCtxt, String subName) throws NamingException {

        String typeDefinition = getTypeDefinition(properties);
        if (false && typeDefinition != null) {
            processTypedProperty(properties, subContext, subName);
        }
        else {
            // NOTE: "type" effectively turns on pseudo-nodes; if it
            //       isn't there then other pseudo-nodes will result
            //       in re-bind errors

            // scan for pseudo-nodes, aka "type": foo.type
            // store in a temporary type table (typeMap): {foo: {type: typeValue}}
            Map typeMap = extractTypedProperties(properties);
            Iterator iterator;

            // If it matches a type root, then it should be added to the properties. If not, then it should be placed in the context (jndiPut()).
            // For each type properties call convert: pass a Properties in that contains everything starting with foo, but without the foo.
            // Put objects in context.
            iterator = properties.keySet().iterator();
            while(iterator.hasNext()) {
                String key = (String) iterator.next();
                Object value = properties.get(key);
                final String delimiter = extractDelimiter(key);
                if (!key.equals("type") && extractTypeDeclaration(key) == null) {
                    if (typeMap.containsKey("datasourceOrBeanProperty")) {
                        // files with a property named "type" without a namespace in the name.
                        ((Properties) typeMap.get("datasourceOrBeanProperty")).put(key, value);
                    }
                    else if(typeMap.containsKey(key)) {
                        // Reached only by keys with basic type declarations like type=java.lang.Integer.
                        // Gets processed by a converter.
                        ((Properties) typeMap.get(key)).put("valueToConvert", value);
                    }
                    else if(delimiter != null) {
                        String pathText = removeLastElement(key, delimiter);
                        String nodeText = getLastElement(key, delimiter);
                        if(typeMap.containsKey(pathText)) {
                            ((Properties) typeMap.get(pathText)).put(nodeText, value);
                        }
                        else {
                            jndiPut(subContext, key, value);
                        }
                    }
                    else {
                        jndiPut(subContext, key, value);
                    }
                }
            }

            for (Object key : typeMap.keySet()) {
                String typeKey = (String) key;
                Properties typeProperties = (Properties) typeMap.get(typeKey);
                Object value = convert(typeProperties);
                if (typeKey.equals("datasourceOrBeanProperty")) {
                    // Reached only by datasource and bean declarations? Yes, but not always! Not from org.osjava.sj.memory.JndiLoaderTest.testBeanConverter(). testBeanConverter() enters the "else" branch.  Not reached, when the attributes are prefixed with a namespace as in roots/datasource/ds.properties (used in SimpleJndiNewTest.sharedContextWithDataSource2MatchingDelimiter()).
                    // rebind(): For every file there is already a context created and bound under the file's name. In case of bean or datasource declarations the binding must not be a context but the value (the bean, the datasource) itself. This is true as long as the datasource or bean properties are not namespaced. Then the "else" branch is executed.
                    parentCtxt.rebind(subName, value);
                }
                else {
                    jndiPut(subContext, typeKey, value);
                }
            }
        }


    }

    @NotNull
    Map<String, Properties> extractTypedProperties(Properties properties) {
        Map typeMap = new HashMap<String, Properties>();
        Iterator iterator = properties.keySet().iterator();
        while(iterator.hasNext()) {
            String key = (String) iterator.next();
            final String type = extractTypeDeclaration(key);
            // key.equals("type"): type attribute without namespace
            // type != null: type attribute prefixed with namespace, e.g. "Sybase/type"
            if(key.equals("type") || type != null) {
                Properties props = new Properties();
                props.put("type", properties.get(key));
                if(key.equals("type")) {
                    // Reached only by datasource and bean declarations? Yes, but not always! Not from org.osjava.sj.memory.JndiLoaderTest.testBeanConverter(). testBeanConverter() enters the "else" branch. Not reached, when the attributes are prefixed with a namespace as in roots/datasource/ds.properties (used in SimpleJndiNewTest.sharedContextWithDataSource2MatchingDelimiter()).
                    typeMap.put("datasourceOrBeanProperty", props);
                }
                else {
                    final String keyWithoutType = key.substring(0, key.length() - type.length() - 1);
                    typeMap.put(keyWithoutType, props);
                }
            }
        }
        return typeMap;
    }

    private void processTypedProperty(Properties properties, Context subContext, String subName) throws NamingException {
        // TODO Hier müssen irgendwie DataSource definitions unterschieden werden von basic type definitions mit unterschiedlich tief verschachtelten namespaces.
        // DataSource and beans without namespaced attributes
        if (isNotNamespacedTypeDefinition(properties)) {
            String typeDefinition = getTypeDefinition(properties);
            Name contextName = extractContextName(typeDefinition);
            Context deepestCtx = subContext;
            Name objName;
            if (contextName != null) {
                if (contextName.size() > 1) {
                    contextName.remove(contextName.size() - 1); // last part is the name of the object to bind.
                    deepestCtx = createSubContexts(contextName, subContext);
                    objName = contextName.getSuffix(contextName.size() - 1);
                }
                else {
                    objName = contextName;
                }
            }
            else {
                objName = new CompoundName(subName, EMPTY_PROPERTIES);
            }

            Properties notNamespacedKeys = new Properties();
            for (Object k : properties.keySet()) {
                String key = (String) k;
                key = extractObjectName(key);
                String value = (String) properties.get(key);
                notNamespacedKeys.put(key, value);
            }
            jndiPut(deepestCtx, objName.toString(), convert(notNamespacedKeys));
        }
        else {
            throw new RuntimeException("Not implemented yet.");
        }
//        Name contextName = extractContextName(typeDefinition);
//        Context deepestCtx = subContext;
//        Name objName;
//        if (contextName != null) {
//            if (contextName.size() > 1) {
//                contextName.remove(contextName.size() - 1); // last part is the name of the object to bind.
//                deepestCtx = createSubContexts(contextName, subContext);
//                objName = contextName.getSuffix(contextName.size() - 1);
//            }
//            else {
//                objName = contextName;
//            }
//        }
//        else {
//            objName = new CompoundName(subName, EMPTY_PROPERTIES);
//        }
//
//        Properties notNamespacedKeys = new Properties();
//        for (Object k : properties.keySet()) {
//            String key = (String) k;
//            key = extractObjectName(key);
//            Object value = properties.get(key);
//            if (value != null) {
//                notNamespacedKeys.put(key, value);
//            }
//        }
//        jndiPut(deepestCtx, objName.toString(), convert(notNamespacedKeys, objName.toString()));
    }

    /**
     *
     * @return null: No type attribute found.
     */
    @Nullable
    String getTypeDefinition(@NotNull Properties properties) {
        for (Object k : properties.keySet()) {
            String key = (String) k;
            if (key.endsWith("type")) {
                return key;
            }
        }
        return null;
    }

    /**
     * If the attribute name is namespaced as in "my/context/objectName", the returned Name is "my/context", because objectName is interpreted not as a context name but as the name of the object to be bound to "my/context".
     *
     * @return null: Not a namespaced attribute, eg. only "myInt".
     */
    @Nullable
    Name extractContextName(String path) throws InvalidNameException {
        Properties envCopy = new Properties(envAsProperties);
        envCopy.setProperty("jndi.syntax.separator", envAsProperties.getProperty(SIMPLE_DELIMITER));
        CompoundName name = new CompoundName(path, envCopy);
        Name nameWithoutObjectName = name.size() > 1
                ? name.getPrefix(name.size() - 1)
                : null;
        return nameWithoutObjectName;
    }

    String extractObjectName(String path) throws InvalidNameException {
        Properties envCopy = new Properties(envAsProperties);
        envCopy.setProperty("jndi.syntax.separator", envAsProperties.getProperty(SIMPLE_DELIMITER));
        CompoundName name = new CompoundName(path, envCopy);
        return name.getSuffix(name.size() > 1 ? name.size() - 1 : 0).toString();
    }

    /**
     * Experimental: Let SIMPLE_DELIMITER be a regular expression, e.g. "\.|\/".
     *
     * @return delimiter "." or "/" or whatever is found by {@link #SIMPLE_DELIMITER}.
     */
    private String extractDelimiter(String key) {
        String delimiter = (String) environment.get(SIMPLE_DELIMITER);
        if (delimiter.length() == 1) { // be downwards compatible
            delimiter = delimiter.replace(".", "\\.");
        }
        // TODO Compile once
        final Pattern pattern = Pattern.compile("^.+(" + delimiter + ").+");
        final Matcher matcher = pattern.matcher(key);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     *
     * @return "type" | null
     */
    private String extractTypeDeclaration(String key) {
        String delimiter = (String) environment.get(SIMPLE_DELIMITER);
        if (delimiter.length() == 1) { // be downwards compatible
            delimiter = delimiter.replace(".", "\\.");
        }
        // TODO Compile once
        final Pattern pattern = Pattern.compile(".+[/." + delimiter + "]type$");
        final Matcher matcher = pattern.matcher(key);
        String type = null;
        if (matcher.find()) {
            type = "type";
        }
        return type;
    }

    /**
     *
     * @param key see {@link #createSubContexts(String[], Context)}
     */
    private void jndiPut(Context ctxt, String key, Object value) throws NamingException {
        String[] pathParts = Utils.split(key, (String) environment.get(SIMPLE_DELIMITER));
        Context deepestContext = ctxt;
        if (pathParts.length > 1) { // 1: No subcontexts need to be created.
            deepestContext = createSubContexts(pathParts, ctxt);
        }
        String name = pathParts[pathParts.length - 1];
        deepestContext.bind(new CompoundName(name, EMPTY_PROPERTIES), value);
    }

    /**
     * Creates contexts defined by namespaced property names, e.g. "my.namespaced.object=...". The last part (here "object") is ignored as name of the object to be bound to "my.namespaced".
     *
     * @return the deepest context
     */
    private Context createSubContexts(String[] path, Context parentContext) throws NamingException {
        int lastIndex = path.length - 1;
        Context currentCtx = parentContext;
        // Not <=: lastIndex is the name of the object to bind.
        for(int i=0; i < lastIndex; i++) {
            Object obj = null;
            try {
                obj = currentCtx.lookup(path[i]);
            }
            catch (NamingException ignore) {
                // If jndi.syntax.separator is "/" and org.osjava.sj.delimiter is "." a Exception is thrown here, when a CompoundName of size > 1 is looked up.
            }
            if(obj == null) {
                currentCtx = currentCtx.createSubcontext(path[i]);
            }
            else if (obj instanceof Context) {
                currentCtx = (Context) obj;
            }
            else {
                throw new RuntimeException("Illegal node/branch clash. At branch value '"+path[i]+"' an Object was found: " +obj);
            }
        }
        return currentCtx;
    }

    /**
     *
     * @param name Name of the contexts to be created in parentContext.
     */
    Context createSubContexts(Name name, Context parentContext) throws NamingException {
        Context currentCtx = parentContext;
        for(int i=0; i < name.size(); i++) {
            Object obj = currentCtx.lookup(name.get(i));
            if(obj == null) {
                currentCtx = currentCtx.createSubcontext(name.get(i));
            }
            else if (obj instanceof Context) {
                currentCtx = (Context) obj;
            }
            else {
                throw new RuntimeException("Illegal node/branch clash. At branch value '" + name.get(i) + "' an Object was found: " +obj);
            }
        }
        return currentCtx;
    }

    private static Object convert(Properties properties) {
        String type = properties.getProperty("type");
        String converterClassName = properties.getProperty("converter");
        if (converterClassName != null) {
            try {
                Class converterClass = Class.forName(converterClassName);
                Converter converter = (Converter) converterClass.newInstance();
                return converter.convert(properties, type);
            }
            catch (ClassNotFoundException cnfe) {
                throw new RuntimeException("Unable to find class: " + converterClassName, cnfe);
            }
            catch (IllegalAccessException ie) {
                throw new RuntimeException("Unable to access class: " + type, ie);
            }
            catch (InstantiationException ie) {
                throw new RuntimeException("Unable to create Converter " + type + " via empty constructor. ", ie);
            }
        }

        Converter converter = converterRegistry.getConverter(type);
        if(converter != null) {
            final Object values = properties.get("valueToConvert");
            if (values instanceof List) {
                List<String> vals = (List<String>) values;
                final LinkedList converted = new LinkedList();
                for (String val : vals) {
                    final Properties props = new Properties();
                    props.setProperty("valueToConvert", val);
                    converted.add(converter.convert(props, type));
                }
                return converted;
            }
            else {
                return converter.convert(properties, type);
            }
        }
        return properties.get("valueToConvert");

    }

    private static String getLastElement( String str, String delimiter ) {
        int idx = str.lastIndexOf(delimiter);
        return str.substring(idx + 1);
    }
    private static String removeLastElement( String str, String delimiter ) {
        int idx = str.lastIndexOf(delimiter);
        return str.substring(0, idx);
    }

}
