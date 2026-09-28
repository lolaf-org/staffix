# staffix-fix-50sp1

This artifact holds a FIX dictionary and no code, so it has no javadoc.

The dictionary is the classpath resource `FIX50SP1.xml`. Generate encoders from it with
`staffix-fix-encoders-generator-maven-plugin`, listing this artifact among the plugin's dependencies and setting
`dictionaryFile` to `classpath:FIX50SP1.xml`.

See https://github.com/lolaf-org/staffix/blob/main/docs/fix-versions-and-dictionaries.md
