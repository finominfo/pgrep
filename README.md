# pgrep

**This util searches parallelly from zip and/or normal text files.**


**Usage:**

java -jar pgrep-1.0-SNAPSHOT.jar

**Configuration (pgrep.properties):**
- max-threads - sets the number of threads can be used
- max-size - sets the maximum size of all recently read files (in bytes)
- max-files - sets the maximum number of files wich is read at the same time 

**ids.txt**
- This file contains the expressions will be looked for

**./zip**
- In this folder the files should be copied which will be searched.
- The zipped (or gzipped) file names should end with .zip
