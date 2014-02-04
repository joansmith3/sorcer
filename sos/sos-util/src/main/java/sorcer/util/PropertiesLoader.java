package sorcer.util;

import java.io.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * @author Rafał Krupiński
 */
public class PropertiesLoader {
	public Map<String, String> loadAsMap(Class c) {
        String name = c.getName();
        String path = name.replace('.', '/') + ".properties";
        return loadAsMap(path, c.getClassLoader());
	}

	public Map<String, String> loadAsMap(String path, ClassLoader cl) {
		return toMap(loadAsProperties(path, cl));
	}

	public Properties loadAsProperties(String path, ClassLoader cl) {
		InputStream stream = cl.getResourceAsStream(path);
		if (stream == null) {
            throw new IllegalArgumentException("Could not load file " + path + " in " + cl);
        }
		Properties result = new Properties();
		try {
			result.load(stream);
		} catch (IOException e) {
			throw new IllegalArgumentException("Could not load file " + path, e);
		} finally {
			try {
				stream.close();
			} catch (IOException e) {
				//ignore
			}
		}
		return result;
	}

    public Map<String, String> loadAsMap(File inputFile) {

        Map<String, String> propertyMapVer = new HashMap<String, String>();
        if (inputFile.exists()) {
            try {
                Properties props = new Properties();
                props.load(new FileInputStream(inputFile));
                propertyMapVer = (Map) props;
            } catch (IOException fe) {

            }
        }
        return propertyMapVer;
    }

	@SuppressWarnings("unchecked")
	public static Map<String, String> toMap(Properties properties) {
        return (Map)properties;
	}
}
