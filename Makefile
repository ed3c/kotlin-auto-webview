.PHONY: test desktop web android ios check

test:
	./gradlew :composeApp:allTests

desktop:
	./gradlew :composeApp:run

web:
	./gradlew :composeApp:wasmJsBrowserDevelopmentRun

android:
	./gradlew :composeApp:assembleDebug

ios:
	./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64

check: test
	./gradlew :composeApp:compileKotlinDesktop :composeApp:wasmJsBrowserDistribution :composeApp:assembleDebug
