Shader "Custom/CameraOES"
{
    Properties
    {
        _MainTex ("Texture", 2D) = "white" {}
        // Ползунки для настройки картинки прямо в Unity
        _Gamma ("Gamma (0.45 = sRGB fix)", Range(0.1, 3.0)) = 0.45
        _Exposure ("Exposition", Range(0.0, 5.0)) = 1.2
        _Contrast ("Contrast", Range(0.0, 3.0)) = 1.2
        _Saturation ("Saturation", Range(0.0, 3.0)) = 1.5
    }
    SubShader
    {
        Tags { "RenderType"="Opaque" }
        LOD 100
        
        Pass
        {
            GLSLPROGRAM
            #pragma only_renderers gles3
            #extension GL_OES_EGL_image_external_essl3 : require

            #ifdef VERTEX
            in vec4 _glesVertex;
            in vec4 _glesMultiTexCoord0;
            out vec2 uv;
            uniform vec4 _MainTex_ST;
            
            // Закомментировано, чтобы избежать конфликта с Adreno
            // uniform mat4 unity_ObjectToWorld;
            // uniform mat4 unity_MatrixVP;
            
            void main()
            {
                gl_Position = unity_MatrixVP * unity_ObjectToWorld * _glesVertex;
                
                // Исправляем UV и переворачиваем по вертикали (если нужно)
                uv = _glesMultiTexCoord0.xy * _MainTex_ST.xy + _MainTex_ST.zw;
                
                // Если картинка вверх ногами - раскомментируйте строку ниже:
                uv.y = 1.0 - uv.y; 
            }
            #endif

            #ifdef FRAGMENT
            in vec2 uv;
            uniform samplerExternalOES _MainTex;
            
            // Переменные настроек
            uniform float _Gamma;
            uniform float _Exposure;
            uniform float _Contrast;
            uniform float _Saturation;
            
            out vec4 fragColor;
            
            // Функция для насыщенности
            vec3 adjustSaturation(vec3 color, float saturation) {
                // Коэффициенты восприятия яркости глазом
                float grey = dot(color, vec3(0.299, 0.587, 0.114));
                return mix(vec3(grey), color, saturation);
            }
            
            void main()
            {
                vec4 col = texture(_MainTex, uv);
                
                // 1. Применяем Гамму (Самое важное для исправления серости)
                // Возводим в степень (обычно 1/2.2 ≈ 0.45)
                col.rgb = pow(col.rgb, vec3(_Gamma));
                
                // 2. Яркость
                col.rgb = col.rgb * _Exposure;
                
                // 3. Контраст
                col.rgb = (col.rgb - 0.5) * _Contrast + 0.5;
                
                // 4. Насыщенность
                col.rgb = adjustSaturation(col.rgb, _Saturation);
                
                fragColor = col;
            }
            #endif
            ENDGLSL
        }
    }
}