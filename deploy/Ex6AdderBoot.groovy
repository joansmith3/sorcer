/**
 * Deployment configuration for ex6-prv
 *
 * @author Pawel Rubach
 */
import sorcer.core.SorcerEnv;

String[] getInitialMemberGroups() {
    def groups = SorcerEnv.getLookupGroups();
    return groups as String[]
}

def getSorcerHome() {
    return sorcerHome = SorcerEnv.getHomeDir();
}

def getSorcerVersion() {
    return sorcerVersion = SorcerEnv.getSorcerVersion();
}

def String getCodebase() {
    return 'http://'+SorcerEnv.getLocalHost().getHostAddress()+":9010"
}


deployment(name: 'ex6-adder') {
    groups getInitialMemberGroups();

    codebase getCodebase()

    artifact id:'ex6-api', 'org.sorcersoft.sorcer:ex6-api:'+getSorcerVersion()
    artifact id:'ex6-prv', 'org.sorcersoft.sorcer:ex6-prv:'+getSorcerVersion()

    service(name:'ex6-adder-prv') {
         interfaces {
             classes 'sorcer.arithmetic.provider.Adder'
             artifact ref:'ex6-api'
         }
         implementation(class: 'sorcer.core.provider.ServiceTasker') {
             artifact ref:'ex6-prv'
         }
         configuration file: "${getSorcerHome()}/examples/ex6/ex6-prv/src/main/resources/config/adder-prv.config"
         maintain 1
     }
}