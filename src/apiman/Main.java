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

/**
 * Console entering point of APIMan.
 * 
 * @author Dante Wang
 */
public class Main {

	/**
	 * @param args the command line arguments
	 */
	public static void main(String[] args) {
		printInfo();

		if (args.length > 1) {
			printUsage();

			System.exit(0);
		}
		else if (args.length == 1)
		{
			ApiMan.instance().run(args[0]);
		}
		else {
			ApiMan.instance().run(null);
		}
	}

		// print usage information on console
	private static void printInfo() {
		System.out.println("\nApiMan: Scanner for liferay APIs.\n" +
			"");
	}

	private static void printUsage() {
		System.err.println("Too many arguments! \n" +
			"\nUsage: ${apiman} [Properties File].\n" +
			"\t[Properties File]: specify a properties file." +
			"\t\tIf not specified, ApiMan will load ./apiman.properties,\n" +
			"\t\tor there will be an error.");
	}
}