/*
 * Open source software by Cove Software, LLC.
 */
package decodes.datasource;

import java.io.BufferedInputStream;
import java.net.URL;

import decodes.util.PropertiesOwner;
import decodes.util.PropertySpec;
import ilex.util.Logger;
import ilex.util.PropertiesUtil;

/**
 * Extends StreamDataSource to read data from an URL opened on the internet.
 * @author mmaloney
 */
public class WebDataSource 
	extends StreamDataSource
	implements PropertiesOwner
{
	private String module = "WebDataSource";
	private String activeAddr = null;
	
	// no args ctor for instantiation via class
	public WebDataSource() {}

	private PropertySpec propSpecs[] =
	{
		new PropertySpec("url", PropertySpec.STRING, "The page to open")
	};
	
	@Override
	public PropertySpec[] getSupportedProps()
	{
		return PropertiesUtil.combineSpecs(super.getSupportedProps(), propSpecs);
	}

	/**
	 * Base class return true for backward compatibility.
	 */
	@Override
	public boolean additionalPropsAllowed()
	{
		return true;
	}


	/** Don't re-read after an URL is finished. */
	@Override
	public boolean tryAgainOnEOF()
	{
		return false;
	}

	/** Likewise -- don't reopen an URL. */
	@Override
	public boolean doReOpen() { return false; }
	
	@Override
	public boolean setProperty(String name, String value)
	{
		if (name.equalsIgnoreCase("url"))
		{
			activeAddr = value;
			return true;
		}
		return false;
	}

	@Override
	public BufferedInputStream open()
		throws DataSourceException
	{
		try
		{
			Logger.instance().info(module + " Will open: '" + activeAddr + "'");
			return new BufferedInputStream((new URL(activeAddr)).openStream());
		}
		catch(Exception ex)
		{
			throw new DataSourceException(module + " Open failed on '" + activeAddr
				+ "': " + ex);
		}
	}

	@Override
	public void close(BufferedInputStream str)
	{
		// silently close
		try 
		{
			str.close();
		}
		catch(Exception ex)
		{}
	}

	@Override
	public String getActiveSource()
	{
		return activeAddr;
	}
}