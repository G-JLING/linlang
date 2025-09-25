package core.linlang.file.runtime;

public final class Names {
    private Names(){}
    public static String toKebab(String s){
        StringBuilder out=new StringBuilder();
        for (int i=0;i<s.length();i++){
            char c=s.charAt(i);
            if (Character.isUpperCase(c)){
                if (i>0 && s.charAt(i-1)!='-') out.append('-');
                out.append(Character.toLowerCase(c));
            } else out.append(c);
        }
        return out.toString();
    }
}