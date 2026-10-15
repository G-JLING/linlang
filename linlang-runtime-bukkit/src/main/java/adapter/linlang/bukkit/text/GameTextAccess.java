package adapter.linlang.bukkit.text;

import net.kyori.adventure.text.Component;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 缓存公开组件接口，按运行环境启用可选能力。
 */
final class GameTextAccess {

    private record Signature(Class<?> type, String name, java.util.List<Class<?>> arguments) {
    }

    private static final ConcurrentHashMap<Signature, Optional<Method>> METHODS = new ConcurrentHashMap<>();

    private GameTextAccess() {
    }

    static Class<?> optionalClass(String name) {
        try {
            return Class.forName(name, false, GameTextAccess.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError ignored) {
            return null;
        }
    }

    static Method method(Class<?> type, String name, Class<?>... arguments) {
        Signature signature = new Signature(type, name, java.util.List.of(arguments));
        return METHODS.computeIfAbsent(signature, key -> {
            try {
                return Optional.of(type.getMethod(name, arguments));
            } catch (NoSuchMethodException ignored) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    static Object invoke(Method method, Object target, Object... arguments) {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException cause) throw cause;
            if (exception.getCause() instanceof Error cause) throw cause;
            throw new IllegalStateException("Game text API invocation failed: " + method, exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Game text API access failed: " + method, exception);
        }
    }

    static boolean componentProperty(Object target, Class<?> type, String name,
                                     java.util.function.UnaryOperator<Component> render) {
        Method read = method(type, name);
        Method write = method(type, name, Component.class);
        if (read == null || write == null) return false;
        Component source = (Component) invoke(read, target);
        if (source != null && GameTextComponents.contains(source)) invoke(write, target, render.apply(source));
        return true;
    }
}
