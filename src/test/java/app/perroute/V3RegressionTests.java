package app.perroute;

/** Runs upstream v3 suites independently of legacy Windows-path tests and the Windows-only real core. */
public final class V3RegressionTests {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Choose domain or controller");
        switch (args[0]) {
            case "domain" -> {
                V3DomainTests.runAll();
                System.out.println("V3 domain tests passed (" + V3DomainTests.assertions() + " assertions)");
            }
            case "controller" -> {
                V3ControllerTests.runAll();
                System.out.println("V3 controller tests passed (" + V3ControllerTests.assertions() + " assertions)");
            }
            default -> throw new IllegalArgumentException("Unknown suite: " + args[0]);
        }
        System.exit(0);
    }
}
