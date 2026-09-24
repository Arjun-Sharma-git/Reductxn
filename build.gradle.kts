plugins {
    id("reductxn.java-conventions") apply false
    id("reductxn.spring-conventions") apply false
    id("reductxn.testing-conventions") apply false
}

// RULE B9: no module declares its own compiler settings. They come from build-logic.
allprojects {
    group = "io.reductxn"
    version = "0.1.0-SNAPSHOT"
}
