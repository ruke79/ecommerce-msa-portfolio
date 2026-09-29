import { useState } from "react";

const MainSwiper = ({ images, activeImg }) => {
    const [active, setActive] = useState(0);

    return (
        <div className="flex flex-col md:col-span-3 md:flex-row-reverse px-2">
            <div className="flex-grow relative">
                <img
                    src={activeImg || images[active]}
                    alt={images[active]}
                    width={400}
                    height={400}
                    className="object-cover"
                />
            </div>
            <div className="flex max-md:mt-2 md:mr-2 md:flex-col gap-2">
                {images.map((img, i) => (
                    <div
                        key={img.url}
                        className={`cursor-pointer gap-2 relative w-10 ${
                            i === active &&
                            "outline outline-1 outline-offset-2 outline-slate-600 rounded"
                        }`}
                        onMouseOver={() => setActive(i)}
                    >
                        <img className="rounded" width={50} height={50} src={img} alt={img} />
                    </div>
                ))}
            </div>
        </div>
    );
};

export default MainSwiper;