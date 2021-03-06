package sorcer.boot;

import org.rioproject.impl.opstring.OAR;
import org.rioproject.impl.opstring.OARException;
import sorcer.core.SorcerEnv;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.jar.JarFile;

/**
 * @author Rafał Krupiński
 */
public class SorcerOAR extends OAR {
    static final String POLICY = "service.policy";

    //TODO Policy files cannot be read from jar
    private File policyFile = new File(SorcerEnv.getHomeDir(), "configs/sorcer.policy");

    public SorcerOAR(File file) throws OARException, IOException {
        super(file);
        readFile(file);
    }

    private void readFile(File file) throws IOException {
/*
        JarFile jar = new JarFile(file);
        if (jar.getJarEntry(POLICY) != null) {
            policyFile = new URL("jar:" + file.toURI().toURL().toExternalForm() + "!/" + POLICY);
        }
*/
    }

    public File getPolicyFile() {
        return policyFile;
    }
}
