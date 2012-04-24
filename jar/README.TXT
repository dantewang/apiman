=============
APIMAN README
=============

Usage:

To run apiman, execute 
    ./apiman.sh [path to the properties file]

in terminal in Linux, or
    java -jar "APIMan.jar" [path to the properties file]

in Windows cmd.

The [path to the properties file] is not necessary,
if "apiman.properties" is together with APIMan.jar.


Properties:

  For both "old" and "new", there are three properties:
    .jar:        the path to the portal-service.jar file (APIMan only supports it for now)
    .classpath:  the path to the .classpath file in a Liferay Repo
    .output:     the file to store the method list of this jar file

  regexp: regular expression to filter some class when scanning the jar

  For diff, just output "deleted" ones (modified ones are treated as deleted)
    .ignore:                  a comma seperated list for things that should be ignored from diff
    .ignore.deleted.classes:  true or false -- whether to output deleted classes
    .output:                  the file to store the deleted method list
