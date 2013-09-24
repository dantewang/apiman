/**
 * Copyright (c) 2000-2011 Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package apiman;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

/**
 * The main class of APIMan.
 * 
 * @author Dante Wang
 */
public class ApiMan {

	private ApiMan() {
		// private constructor
	}

	public static ApiMan instance() {
		return _apiman;
	}

	public void run(String propertiesFile) {
		long start = System.nanoTime();

		if (propertiesFile == null) {
			propertiesFile = _PROPERTIES_FILE;
		}

		Properties properties = _processProperties(propertiesFile);

		String pattern = properties.getProperty(_KEY_REGEXP);

		String fileProtocol = properties.getProperty(_KEY_FILE_PROTOCOL);

		String delimiter = 
			properties.getProperty(_KEY_DIFF_IGNORE_DELIMITER);

		List<String> ignores = _parseStringToList(
			properties.getProperty(_KEY_DIFF_IGNORE), delimiter);

		boolean ignoreDeletedClasses = Boolean.parseBoolean(
			properties.getProperty(_KEY_DIFF_IGNORE_CLASS));

		for (String ignore : ignores) {
			System.out.println(ignore);
		}

		System.out.printf(
			"Processing %s\nThis may take several seconds.\n\n",
			properties.getProperty(_KEY_OLD_JAR));

		Map<String, Set<String>> oldMap = _generateApiMap(
			properties.getProperty(_KEY_OLD_JAR),
			properties.getProperty(_KEY_OLD_CP), pattern, fileProtocol);

		System.out.printf(
			"Write to %s.\n\n", properties.getProperty(_KEY_OLD_OUTPUT));

		_writeToFile(oldMap, properties.getProperty(_KEY_OLD_OUTPUT));

		System.out.printf(
			"Processing %s\nThis may take several seconds.\n\n",
			properties.getProperty(_KEY_NEW_JAR));

		Map<String, Set<String>> newMap = _generateApiMap(
			properties.getProperty(_KEY_NEW_JAR), 
			properties.getProperty(_KEY_NEW_CP), pattern, fileProtocol);
		
		System.out.printf(
			"Write to %s.\n\n", properties.getProperty(_KEY_NEW_OUTPUT));

		_writeToFile(newMap, properties.getProperty(_KEY_NEW_OUTPUT));

		System.out.printf("Generate deleted method list...\n\n");

		Map<String, Set<String>> diffMap = _generateDiffMap(
			oldMap, newMap, ignores, ignoreDeletedClasses);

		System.out.printf(
			"Write to %s.\n\n", properties.getProperty(_KEY_DIFF_OUTPUT));

		_writeToFile(diffMap, properties.getProperty(_KEY_DIFF_OUTPUT));

		long time = System.nanoTime() - start;

		System.out.printf(
			"Generation finished successfully in %d ms%n.", time / 1000000);
	}

	private Map<String, Set<String>> _generateApiMap(
		String jar, String classpath, String pattern, String fileProtocol) {

		File jarFile = new File(jar);
		File classpathFile = new File(classpath);

		List<String> classpaths = _parseClasspathXML(classpathFile);

		URL[] urls = _generateURLs(classpaths, jarFile, fileProtocol);

		List<String> classNames = _walkTree(jarFile, pattern);

		return _processClasses(urls, classNames);
	}

	private Map<String, Set<String>> _generateDiffMap(
		Map<String, Set<String>> oldMap, Map<String, Set<String>> newMap,
		List<String> ignores, boolean ignoreDeletedClasses) {

		Map<String, Set<String>> diffMap = new TreeMap<String, Set<String>>();

		for (Map.Entry<String, Set<String>> entry : oldMap.entrySet()) {
			String oldKey = entry.getKey();

			if (ignores.contains(oldKey)) {
				continue;
			}

			if (!newMap.containsKey(oldKey)) {
				// so the class represented by the key has been deleted
				// if not ignore deleted class, put it in diff map
				if (!ignoreDeletedClasses) {
					diffMap.put("[Class Deleted] " + oldKey,
						oldMap.get(oldKey));
				}

				continue;
			}

			Set<String> oldValues = oldMap.get(oldKey);
			Set<String> newValues = newMap.get(oldKey);
			Set<String> deletedMethods = new TreeSet<String>();

			// TODO: contains is binary tree search
			for (String value : oldValues) {
				//temp fix to avoid comma problem for methods in ignore list
				boolean isIgnore = false;

				for (String ignore : ignores) {
					if (value.indexOf(ignore) != -1) {
						isIgnore = true;
						break;
					}
				}

				if (isIgnore) {
					continue;
				}

				if (newValues.contains(value)) {
					continue;
				}

				deletedMethods.add(value);
			}

			if (deletedMethods.isEmpty()) {
				continue;
			}

			diffMap.put(oldKey, deletedMethods);
		}

		return diffMap;
	}

	private URL[] _generateURLs(
		List<String> classpaths, File jarFile, String fileProtocol) {

		URL[] classpathURLs = new URL[classpaths.size() + 1];

		try {
			classpathURLs[0] =
				new URL(fileProtocol + jarFile.getAbsolutePath() + "!/");

			for (int i = 1; i < classpathURLs.length; i++) {
				classpathURLs[i] =
					new URL(fileProtocol + classpaths.get(i - 1) + "!/");
			}
		}
		catch (MalformedURLException mue) {
			System.err.printf("Invalid URL Found, Abort running.\n%s\n",
				mue.getMessage());

			System.exit(1);
		}

		return classpathURLs;
	}

	// parse .classpath file using SAX
	private List<String> _parseClasspathXML(File dotClasspathFile) {
		final List<String> classpaths = new ArrayList<String>();

		final String baseDir =
			dotClasspathFile.getAbsoluteFile().getParent().toString();

		SAXParserFactory spf = SAXParserFactory.newInstance();

		spf.setNamespaceAware(true);

		XMLReader parser;

		try {
			parser = spf.newSAXParser().getXMLReader();

			parser.setContentHandler(new DefaultHandler() {

				@Override
				public void startElement(String uri, String localName,
					String qname, Attributes attrs) {

					if (!localName.equals("classpathentry")) {
						return;
					}

					String kind = attrs.getValue("kind");

					if (kind != null && kind.equals("lib")) {
						String jarPath = attrs.getValue("path");

						jarPath = baseDir + "/" + jarPath;

						classpaths.add(jarPath);
					}
				}
			});

			try {
				parser.parse(dotClasspathFile.toURI().toString());
			}
			catch (IOException ioe) {
				System.err.printf(
					"Could not open the .classpath file: %s.\n%s\n",
					dotClasspathFile.getAbsolutePath(), ioe.getMessage());

				System.exit(1);
			}
			catch (SAXException saxe) {
				System.err.printf(
					"Error when parsing the .classpath file.\n%s\n",
					saxe.getMessage());

				System.exit(1);
			}
		}
		catch (SAXException saxe) {
			System.err.println(saxe.getMessage());

			System.exit(1);
		}
		catch (ParserConfigurationException pce) {
			System.err.println(pce.getMessage());

			System.exit(1);
		}

		return classpaths;
	}

	private List<String> _parseStringToList(
		String string, String delimiter) {

		int lastIndex = 0;
		int index;

		List<String> list = Collections.EMPTY_LIST;

		if (string == null || string.isEmpty()) {
			return list;
		}

		list = new ArrayList<String>();

		while ((index = string.indexOf(delimiter, lastIndex)) != -1) {

			String item = string.substring(lastIndex, index).trim();

			if (item.length() > 0) {
				list.add(item);
			}

			lastIndex = index + 1;
		}

		// handle tail
		String tail = null;

		if (lastIndex == 0) {
			tail = string.trim();
		}
		else if (lastIndex < string.length()) {
			tail = string.substring(lastIndex).trim();
		}
		else {
			return list;
		}

		list.add(tail);

		return list;
	}

	// load classes and list methods
	private Map<String, Set<String>> _processClasses(
		URL[] urls, List<String> classNames) {

		// Load class
		ClassLoader clazzLoader = new URLClassLoader(urls);

		Map<String, Set<String>> apiMap =
			new TreeMap<String, Set<String>>();

		for (String className : classNames) {
			try {
				Class clazz = clazzLoader.loadClass(className);

				Method[] methods = clazz.getDeclaredMethods();

				Set<String> methodSet = new TreeSet<String>();

				for (Method method : methods) {
					String methodSignature = method.toString();

					int modifiers = method.getModifiers();

					if (Modifier.isPublic(modifiers)) {
						methodSet.add(methodSignature);
					}
				}

				apiMap.put(className, methodSet);
			}
			catch (ClassNotFoundException cnfe) {
				System.err.printf(
					"A class can not be found, abort running.\n%s\n",
					cnfe.getMessage());

				System.exit(1);
			}
		}

		return apiMap;
	}

	private Properties _processProperties(String propertiesFile) {
		File file = new File(propertiesFile);
		file = file.getAbsoluteFile();

		Properties properties = null;

		try {
			properties = new Properties();
			properties.load(
				new BufferedReader(new FileReader(file)));
		}
		catch (IOException ioe) {
			System.err.printf(
				"Error processing properties file.\n%s\n", ioe.getMessage());

			System.exit(1);
		}

		return properties;
	}

	// iterate through the jar file and get class list
	private List<String> _walkTree(File file, String patternStr) {
		List<String> classNames = new ArrayList<String>();

		Pattern pattern = Pattern.compile(patternStr);

		JarFile jarFile = null;

		try {
			jarFile = new JarFile(file);

			Enumeration<JarEntry> entries = jarFile.entries();

			while (entries.hasMoreElements()) {
				JarEntry jarEntry = entries.nextElement();

				String jarEntryName = jarEntry.getName();

				if (!jarEntryName.endsWith(".class")) {
					continue;
				}

				Matcher matcher = pattern.matcher(jarEntryName);

				if (!matcher.matches()) {
					continue;
				}

				// delete ".class"
				jarEntryName = jarEntryName.substring(0,
					jarEntryName.length() - 6);

				// replace "/" with "."
				String className = jarEntryName.replaceAll("/", "\\.");

				classNames.add(className);
			}

		}
		catch (IOException ioe) {
			System.err.printf(
				"Could not open the specified .jar file: %s.\n%s\n",
				file.getAbsolutePath(), ioe.getMessage());

			System.exit(1);
		}
		finally {
			if (jarFile != null) {
				try {
					jarFile.close();
				}
				catch (IOException ioe) {
					System.err.printf(
						"Error when closing the jar file.\n%s\n",
						ioe.getMessage());
				}
			}
		}

		return classNames;
	}

	private void _writeToFile(
		Map<String, Set<String>> map, String output) {

		File outputFile = new File(output);

		outputFile = outputFile.getAbsoluteFile();

		File parentDir = outputFile.getParentFile();

		if (!parentDir.exists()) {
			if (!parentDir.mkdirs()) {
				System.err.printf(
					"Fail to create parent dir for %s.\n", output);

				System.exit(1);
			}
		}

		BufferedWriter out = null;

		try {
			out = new BufferedWriter(new FileWriter(outputFile));
		}
		catch (IOException ioe) {
			System.err.printf("Cannot open writer: \n%s\n",
				ioe.getMessage());

			System.exit(1);
		}

		if (out != null) {
			for (Map.Entry<String, Set<String>> entry : map.entrySet()) {
				StringBuilder sb = new StringBuilder();

				sb.append(entry.getKey());
				sb.append("\n");

				Set<String> methodSet = entry.getValue();

				for (String methodName : methodSet) {
					sb.append("\t");
					sb.append(methodName);
					sb.append("\n");
				}

				sb.append("\n");

				try {
					out.write(sb.toString());
					out.flush();
				}
				catch (IOException ioe) {
					System.err.printf("Failed writing to output file: \n%s\n",
						ioe.getMessage());

					System.exit(1);
				}
			}

			try {
				out.close();
			}
			catch (IOException ioe) {
				System.err.printf("Cannot close writer: \n%s\n",
					ioe.getMessage());
			}
		}
	}

	private static final ApiMan _apiman = new ApiMan();

	private static final String _PROPERTIES_FILE = "apiman.properties";

	private static final String _KEY_OLD_JAR = "old.jar";
	private static final String _KEY_OLD_CP = "old.classpath";
	private static final String _KEY_OLD_OUTPUT = "old.output";

	private static final String _KEY_NEW_JAR = "new.jar";
	private static final String _KEY_NEW_CP = "new.classpath";
	private static final String _KEY_NEW_OUTPUT = "new.output";

	private static final String _KEY_REGEXP = "regexp";

	private static final String _KEY_DIFF_IGNORE_DELIMITER =
		"diff.ignore.delimiter";
	private static final String _KEY_DIFF_IGNORE = "diff.ignore";
	private static final String _KEY_DIFF_IGNORE_CLASS =
		"diff.ignore.deleted.classes";
	private static final String _KEY_DIFF_OUTPUT = "diff.output";
	
	private static final String _KEY_FILE_PROTOCOL = "file.protocol";

}