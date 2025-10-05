package com.example.adaptive;

public class App {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Usage: App monitor|compact|adaptive");
            System.exit(1);
        }
        switch (args[0]) {
            case "monitor":
                Monitor.main(new String[]{});
                break;
            case "compact":
                Compactor.main(new String[]{});
                break;
            case "adaptive":
                AdaptiveController.main(new String[]{});
                break;
            default:
                System.err.println("Unknown mode: " + args[0]);
        }
    }
}
