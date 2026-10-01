package org.jpy.ffm.fixtures;

/** Static fields the C jpy cannot see from Python; the FFM jpy serves them live. */
public class StaticFieldsFixture {
    public static int counter = 7;
    public static String label = "start";
    public static Object anything = null;
    public static final int CONSTANT = 99;
    /** A field and a method with the same name: the method keeps the name in Python. */
    public static long shared = 1;
    private static int hidden = 5;
    public int instanceField = 3;

    public static long shared() {
        return 2;
    }

    public static int bump() {
        return ++counter;
    }

    public static String getLabel() {
        return label;
    }

    public static int getHidden() {
        return hidden;
    }
}
