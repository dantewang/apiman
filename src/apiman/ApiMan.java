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
		return apiman;
	}

	public void run(String propertiesFile) {
		long start = System.nanoTime();

		if (propertiesFile == null) {
			propertiesFile = PROPERTIES_FILE;
		}

		Properties properties = processProperties(propertiesFile);

		String pattern = properties.getProperty(KEY_REGEXP);

		List<String> ignores = parseStringToList(
			properties.getProperty(KEY_DIFF_IGNORE));

		boolean ignoreDeletedClasses = Boolean.parseBoolean(
			properties.getProperty(KEY_DIFF_IGNORE_CLASS));

		for (String ignore : ignores) {
			System.out.println(ignore);
		}

		System.out.printf("Processing %s\nThis may take several seconds.\n\n",
			properties.getProperty(KEY_OLD_JAR));

		Map<String, Set<String>> oldMap = generateApiMap(
			properties.getProperty(KEY_OLD_JAR),
			properties.getProperty(KEY_OLD_CP), pattern);

		System.out.printf("Write to %s.\n\n",
			properties.getProperty(KEY_OLD_OUTPUT));

		writeToFile(oldMap, properties.getProperty(KEY_OLD_OUTPUT));

		System.out.printf("Processing %s\nThis may take several seconds.\n\n",
			properties.getProperty(KEY_NEW_JAR));

		Map<String, Set<String>> newMap = generateApiMap(
			properties.getProperty(KEY_NEW_JAR), 
			properties.getProperty(KEY_NEW_CP), pattern);
		
		System.out.printf("Write to %s.\n\n",
			properties.getProperty(KEY_NEW_OUTPUT));

		writeToFile(newMap, properties.getProperty(KEY_NEW_OUTPUT));

		System.out.printf("Generate deleted method list...\n\n");

		Map<String, Set<String>> diffMap = generateDiffMap(
			oldMap, newMap, ignores, ignoreDeletedClasses);

		System.out.printf("Write to %s.\n\n",
			properties.getProperty(KEY_DIFF_OUTPUT));

		writeToFile(diffMap, properties.getProperty(KEY_DIFF_OUTPUT));

		long time = System.nanoTime() - start;

		System.out.printf("Generation finished successfully in %d ms%n.",
			time / 1000000);
	}

	private Map<String, Set<String>> generateApiMap(
			String jar, String classpath, String pattern) {

		File jarFile = new File(jar);
		File classpathFile = new File(classpath);

		List<String> classpaths = parseClasspathXML(classpathFile);

		URL[] urls = generateURLs(classpaths, jarFile);

		List<String> classNames = walkTree(jarFile, pattern);

		return processClasses(urls, classNames);
	}

	private Map<String, Set<String>> generateDiffMap(
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
				
				if (ignores.contains(value)) {
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

	private URL[] generateURLs(List<String> classpaths, File jarFile) {
		URL[] classpathURLs = new URL[classpaths.size() + 1];

		try {
			classpathURLs[0] =
				new URL("jar:file://" + jarFile.getAbsolutePath() + "!/");

			for (int i = 1; i < classpathURLs.length; i++) {
				classpathURLs[i] =
					new URL("jar:file://" + classpaths.get(i - 1) + "!/");
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
	private List<String> parseClasspathXML(File dotClasspathFile) {
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

	private List<String> parseStringToList(String string) {
		int lastIndex = 0;
		int index;
		List<String> list = new ArrayList<String>();

		if (string == null || string.isEmpty()) {
			return list;
		}

		while (
			(index = string.indexOf(",", lastIndex)) != -1) {

			String item =
				string.substring(lastIndex, index).trim();

			if (item.length() > 0) {
				list.add(item);
			}

			lastIndex = index + 1;
		}

		// handle tail (the part in the string after the last comma)
		String tail = null;

		if (lastIndex == 0) {
			tail = string.trim();
		}
		else if (lastIndex < string.length()) {
			tail = string.substring(lastIndex).trim();
		}
		else {
			// if there is a comma at the end of the string, ignore it.
			return list;
		}

		list.add(tail);

		return list;
	}

	// load classes and list methods
	private Map<String, Set<String>> processClasses(
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

	private Properties processProperties(String propertiesFile) {
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
	private List<String> walkTree(File file, String patternStr) {
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

	private void writeToFile(
			Map<String, Set<String>> map, String output) {

		BufferedWriter out = null;

		try {
			out = new BufferedWriter(new FileWriter(output));
		}
		catch (IOException ioe) {
			System.err.printf("Cannot open write: \n%s\n",
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

	private static ApiMan apiman = new ApiMan();

	private final String PROPERTIES_FILE = 
		"apiman.properties";
	private final String KEY_OLD_JAR = "old.jar";
	private final String KEY_OLD_CP = "old.classpath";
	private final String KEY_OLD_OUTPUT = "old.output";
	private final String KEY_NEW_JAR = "new.jar";
	private final String KEY_NEW_CP = "new.classpath";
	private final String KEY_NEW_OUTPUT = "new.output";
	private final String KEY_REGEXP = "regexp";
	private final String KEY_DIFF_IGNORE = "diff.ignore";
	private final String KEY_DIFF_IGNORE_CLASS = "diff.ignore.deleted.classes";
	private final String KEY_DIFF_OUTPUT = "diff.output";

}