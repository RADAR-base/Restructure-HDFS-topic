package org.radarcns.util.commandline;


import com.beust.jcommander.IParameterValidator;
import com.beust.jcommander.ParameterException;

public class HdfsUriValidator implements IParameterValidator{
    @Override
    public void validate(String name, String value) throws ParameterException {
        if (! value.matches("((hdfs)|(webhdfs)):(/?/?)[^\\s]+")) {
            throw new ParameterException("Parameter " + name + " should be a valid HDFS or WebHDFS URI. "
                    + "Eg - hdfs://<HOST>:<RPC_PORT>/<PATH>. (found " + value
                    + "). Please run with --help or -h for more information.");
        }
    }
}