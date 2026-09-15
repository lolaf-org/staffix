# staffix-fixt-11

This artifact has no hand-written source and no javadoc.

Everything in it - the FIX message encoders, the field classes and the message type registry - is generated
during the build from the FIX dictionary in `src/main/dictionaries/`, by
`staffix-fix-encoders-generator-maven-plugin`. The generated sources ship in this artifact's `-sources.jar`,
which is the thing to read; javadoc is deliberately not run over them, the generated class count being large
enough that documenting it would cost more than the jar it documents.

See https://github.com/lolaf-org/staffix
