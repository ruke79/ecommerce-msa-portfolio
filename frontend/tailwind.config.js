/** @type {import('tailwindcss').Config} */

module.exports = {
  content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
  theme: {

    screens: {
      'sm': '640px',
      // => @media (min-width: 640px) { ... }

      'md': '768px',
      // => @media (min-width: 768px) { ... }
      'md2': '800px',

      'lg': '1024px',
      // => @media (min-width: 1024px) { ... }

      'xl': '1280px',
      // => @media (min-width: 1280px) { ... }

      '2xl': '1536px',
      // => @media (min-width: 1536px) { ... }
    },

    fontWeight: {
             hairline: 100,
             'extra-light': 100,
             thin: 200,
              light: 300,
              normal: 400,
              medium: 500,
             semibold: 600,
              bold: 700,
             extrabold: 800,
             'extra-bold': 800,
              black: 900,
            }
   , 


    extend: {
      colors: {
        amazon: {
          blue_light: '#232f3e',
          blue_dark: '#131921',
          orange: '#febd69'
        }
      }
    },
  },  
}

