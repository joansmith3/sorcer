package sorcer.config;
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


import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * @author Rafał Krupiński
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface ConfigEntry {
    public String DEFAULT_KEY = "ConfigEntry.DefaultKey";
    public String NONE = "ConfigEntry.None";

    /**
     * Entry name. Default null value makes use of field or property name.
     */
    String value() default DEFAULT_KEY;

    /**
     * The default value, currently only available for strings.
     */
    String defaultValue() default NONE;

    /**
     * if true, configuration entry is required and exception is thrown if it's missing or wrong type. Warning is issued otherwise.
     */
    boolean required() default true;
}