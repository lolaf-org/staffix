# staffix-fix-42

This artifact holds a FIX dictionary and no code, so it has no javadoc.

The dictionary is the classpath resource `FIX42.xml`. Generate encoders from it with
`staffix-fix-encoders-generator-maven-plugin`, listing this artifact among the plugin's dependencies and setting
`dictionaryFile` to `classpath:FIX42.xml`.

See https://github.com/lolaf-org/staffix/blob/main/docs/fix-versions-and-dictionaries.md
