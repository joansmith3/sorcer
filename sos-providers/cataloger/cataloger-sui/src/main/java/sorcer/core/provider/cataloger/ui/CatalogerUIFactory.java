package sorcer.core.provider.cataloger.ui;
/**
 *
 * Copyright 2013 Rafał Krupiński.
 * Copyright 2013 Sorcersoft.com S.A.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


import net.jini.lookup.entry.UIDescriptor;
import net.jini.lookup.ui.MainUI;
import sorcer.core.SorcerEnv;
import sorcer.resolver.Resolver;
import sorcer.ui.serviceui.UIDescriptorFactory;
import sorcer.ui.serviceui.UIFrameFactory;
import sorcer.util.Artifact;

import java.net.URL;

/**
 * Extracted from ServiceCataloger
 * @author Rafał Krupiński
 */
public class CatalogerUIFactory {
    /**
     * Returns a service UI descriptor for a Cataloger UI. The interface
     * presents provider's interfaces of discovered services along with
     * associated contexts per defined interface or individual interface method.
     */
    public static UIDescriptor getMainUIDescriptor() {
        UIDescriptor uiDesc = null;
        try {
            URL uiUrl = Resolver.resolveAbsoluteURL(new URL(SorcerEnv.getWebsterUrl()), Artifact.sorcer("cataloger-sui"));
            URL helpUrl = new URL(SorcerEnv.getWebsterUrl()
                    + "/deploy/cataloger.html");

            // URL exportUrl, String className, String name, String helpFilename
            uiDesc = UIDescriptorFactory.getUIDescriptor(MainUI.ROLE,
                    new UIFrameFactory(new URL[]{uiUrl},
                            CatalogerUI.class.getName(), "Catalog Browser",
                            helpUrl));
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return uiDesc;
    }

}
