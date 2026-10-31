$jars = (Get-ChildItem -Path "C:\Users\R c\.m2\repository" -Filter "*.jar" -Recurse | Where-Object { $_.FullName -notmatch "slf4j-api-1\." } | ForEach-Object { $_.FullName }) -join ";"
$cp = "target\test-classes;target\classes;" + $jars
& "C:\Program Files\Java\jdk-21\bin\java.exe" -cp $cp id.ja_wa.SimpleBot
