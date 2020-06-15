package io.chubao.joyqueue.broker;

import com.jd.laf.extension.ExtensionPoint;
import com.jd.laf.extension.ExtensionPointLazy;
import com.jd.laf.extension.SpiLoader;
import io.chubao.joyqueue.nsr.NameService;

public interface PluginsTest {

    /**
     * 命名服务扩展点
     */
    ExtensionPoint<NameService, String> NAMESERVICE = new ExtensionPointLazy<>(NameService.class, SpiLoader.INSTANCE, null, null);
    /**
     * 命名服务扩展点
     */
    ExtensionPoint<NameService, String> NAMESERVICEB = new ExtensionPointLazy<>(NameService.class, SpiLoader.INSTANCE, null, null);
}
