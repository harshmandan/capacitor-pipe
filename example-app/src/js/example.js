import { NPE } from '@capacitor-community/npe';

window.testEcho = () => {
    const inputValue = document.getElementById("echoInput").value;
    NPE.echo({ value: inputValue })
}
